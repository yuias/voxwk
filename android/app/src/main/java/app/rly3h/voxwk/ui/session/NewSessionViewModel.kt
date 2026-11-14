package app.rly3h.voxwk.ui.session

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rly3h.voxwk.R
import app.rly3h.voxwk.RecordingService
import app.rly3h.voxwk.VoxwkApp
import app.rly3h.voxwk.audio.AudioRouters
import app.rly3h.voxwk.audio.PhoneAudioInput
import app.rly3h.voxwk.audio.listenHasPrivateOutput
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.data.LoggedUtterance
import app.rly3h.voxwk.data.Models
import app.rly3h.voxwk.data.SessionLog
import app.rly3h.voxwk.data.SessionMode
import app.rly3h.voxwk.data.SessionStore
import app.rly3h.voxwk.data.SettingsRepository
import app.rly3h.voxwk.data.requiredModelIds
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.OnlineProvider
import app.rly3h.voxwk.translate.ConversationTranslator
import app.rly3h.voxwk.translate.EngineStatus
import app.rly3h.voxwk.translate.GeminiTranslator
import app.rly3h.voxwk.translate.LiveTurn
import app.rly3h.voxwk.translate.OfflineConversationTranslator
import app.rly3h.voxwk.translate.OfflineTranslator
import app.rly3h.voxwk.translate.OnlineConversationTranslator
import app.rly3h.voxwk.translate.OpenAiTranslator
import app.rly3h.voxwk.translate.PreloadState
import app.rly3h.voxwk.translate.SpeechTranslator
import app.rly3h.voxwk.translate.TranslatorCallbacks
import app.rly3h.voxwk.translate.asrEnginesFor
import app.rly3h.voxwk.translate.mediaOutputTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.mtSupported

/**
 * Owns a live session: its turns, the running engine, and the TTS handle.
 *
 * This lives in a ViewModel rather than the composable because the engine
 * outlives the screen — the capture coroutine runs on [viewModelScope], so
 * opening the drawer, rotating, or stepping into Settings no longer tears the
 * session down mid-turn. The screen scopes it to the Activity for that reason;
 * a fresh session is an explicit action ([reset]), not a side effect of
 * navigation.
 */
internal class NewSessionViewModel(app: Application) : AndroidViewModel(app) {
  private val repository = SettingsRepository(app)
  private val input = PhoneAudioInput(app)

  /**
   * Live settings. Engines get a snapshot taken at [start], so a change here never reaches a
   * running session, except Mute and Speech rate, which they read through lambdas on this flow.
   */
  val settings: StateFlow<AppSettings> =
    repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

  /** Progress of the background model load, shown on the mic button. */
  val preload: StateFlow<PreloadState> = (app as VoxwkApp).preloader.state

  /** Whether a key is stored for the selected provider. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val hasKey: StateFlow<Boolean> = settings.map { it.onlineProvider }.distinctUntilChanged()
    .flatMapLatest { repository.hasApiKey(it) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  // Both online Conversation directions report persistence concurrently; one thread keeps the file writes ordered.
  @OptIn(ExperimentalCoroutinesApi::class)
  private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)

  private var startedAt = System.currentTimeMillis()
  private var sessionId = SessionStore.newId(startedAt)

  // The mode the running session began with; the setting may differ by the time a turn is saved.
  private var sessionMode = SessionMode.LISTEN

  val turns = mutableStateListOf<LiveTurn>() // chronological: newest last
  var status by mutableStateOf<SessionStatus?>(null) // null = idle text for the current mode
    private set
  var partial by mutableStateOf("") // live transcript (streaming mode)
    private set
  var recording by mutableStateOf(false)
    private set
  var speaking by mutableStateOf(false)
    private set

  /**
   * There is no private output when the session began. Kept apart from [status]: the engines
   * report it once, and any later status would replace it on screen.
   */
  var noEarphones by mutableStateOf(false)
    private set

  private var tts: TextToSpeech? = null
  private var translator: SpeechTranslator? = null
  private var startJob: Job? = null

  init {
    tts = TextToSpeech(app) { }
  }

  /**
   * Ignored mid-session. Turns of the other mode are cleared first so one saved log
   * never mixes Listen and Conversation.
   */
  fun onModeChange(value: SessionMode) {
    if (recording || value == settings.value.sessionMode) return
    if (turns.isNotEmpty()) reset()
    status = null
    viewModelScope.launch { repository.update { it.copy(sessionMode = value) } }
  }

  fun onConversationPartnerChange(value: Language) {
    viewModelScope.launch { repository.update { it.copy(conversationPartner = value) } }
  }

  fun onListenSourceChange(value: Language?) {
    viewModelScope.launch { repository.update { it.copy(listenSource = value) } }
  }

  fun onSpeakingChange(value: Boolean) {
    speaking = value
    (translator as? ConversationTranslator)?.setSpeaking(value)
  }

  fun onAutoSpeakChange(value: Boolean) {
    viewModelScope.launch { repository.update { it.copy(autoSpeak = value) } }
  }

  fun onOnlineChange(value: Boolean) {
    viewModelScope.launch { repository.update { it.copy(onlineEnabled = value) } }
  }

  /** Drops the accumulated turns and starts logging under a fresh session id. */
  fun reset() {
    if (recording) return
    turns.clear()
    partial = ""
    noEarphones = false
    startedAt = System.currentTimeMillis()
    sessionId = SessionStore.newId(startedAt)
    status = null
  }

  /**
   * Starts capture. [network] is the screen's live connectivity; the engine choice is
   * snapshotted for the whole session, so toggling mid-run is not supported.
   */
  fun start(network: Boolean) {
    if (recording) return
    recording = true // claims the start: the checks below suspend
    startJob = viewModelScope.launch {
      val app = getApplication<Application>()
      val s = repository.settings.first()
      sessionMode = s.sessionMode
      val conversation = s.sessionMode == SessionMode.CONVERSATION
      val mine = s.myLanguage
      // Listen translates into my language and hears the source (Auto = unknown); Conversation does both ways.
      val targets = if (conversation) listOf(mine, s.conversationPartner) else listOf(mine)
      val spoken = if (conversation) targets else listOfNotNull(s.listenSource)
      // Tink/Keystore decryption blocks, and a key is only needed when online is switched on.
      val apiKey = if (s.onlineEnabled) withContext(Dispatchers.IO) { repository.apiKey(s.onlineProvider) } else null
      val useOnline = s.onlineEnabled && onlineAvailable(network, apiKey != null, s.onlineProvider, targets)

      if (!useOnline) {
        val blocked = when {
          requiresOnline(spoken) ->
            SessionStatus.Message(R.string.status_needs_online, listOf(spoken.first { !it.offlineInput }))
          !withContext(Dispatchers.IO) { mtSupported() } ->
            SessionStatus.Message(R.string.status_mt_unsupported)
          withContext(Dispatchers.IO) { requiredModelIds(asrEnginesFor(s.sessionMode, s.listenSource, mine, s.conversationPartner, s.jaRecognizer), s.mtModel).any { !Models.isPresent(app, it) } } ->
            SessionStatus.Message(R.string.status_models_missing)
          else -> null
        }
        if (blocked != null) {
          status = blocked
          recording = false
          return@launch
        }
      }

      // Conversation learns this from its router; Listen has no router, so check the outputs here.
      noEarphones = !conversation && !listenHasPrivateOutput(mediaOutputTypes(app))
      val engine: SpeechTranslator
      if (conversation) {
        // Create the router before the service starts: the service records the router
        // generation in onStartCommand to scope its onDestroy safety net to this session.
        val router = AudioRouters.create(app)
        engine = if (useOnline) {
          OnlineConversationTranslator(app, apiKey!!, s, ::autoSpeakNow, s.onlineProvider, mine, s.conversationPartner, input, router)
        } else {
          OfflineConversationTranslator(app, s, ::autoSpeakNow, ::speechRateNow, input, mine, s.conversationPartner, router)
        }
        speaking = false // every session starts on the partner's side; Speaking is pressed to talk
      } else {
        engine = when {
          !useOnline -> OfflineTranslator(app, s, input, s.listenSource, mine)
          s.onlineProvider == OnlineProvider.GEMINI -> GeminiTranslator(app, apiKey!!, s, ::autoSpeakNow, mine, input, s.listenSource)
          else -> OpenAiTranslator(app, apiKey!!, s, ::autoSpeakNow, mine, input, s.listenSource)
        }
      }
      translator = engine
      // Started while the screen is visible, which is what API 34+ requires of a
      // microphone-typed foreground service.
      RecordingService.onStopRequested = ::stop
      RecordingService.start(app)
      status = SessionStatus.Engine(if (useOnline) EngineStatus.Connecting else EngineStatus.Listening)
      engine.start(viewModelScope, callbacksFor(engine))
    }
  }

  private fun autoSpeakNow() = settings.value.autoSpeak

  private fun speechRateNow() = settings.value.speechRate

  fun stop() {
    if (!recording) return
    val engine = translator
    if (engine == null) {
      // Still in the pre-start checks: nothing to wind down, so go straight back to idle.
      startJob?.cancel()
      startJob = null
      recording = false
      status = null
      return
    }
    engine.stop()
    status = SessionStatus.Message(R.string.status_finishing)
  }

  override fun onCleared() {
    translator?.stop()
    translator = null
    releaseService()
    tts?.let { it.stop(); it.shutdown() }
    tts = null
  }

  private fun releaseService() {
    RecordingService.onStopRequested = null
    RecordingService.stop(getApplication())
  }

  /**
   * Engines call this from background threads, so the turn list is read on Main and only the
   * file write leaves it, on the single-thread dispatcher.
   */
  private fun persist() {
    viewModelScope.launch(Dispatchers.Main.immediate) {
      val done = turns.filter { it.translation != null } // already chronological
      if (done.isEmpty()) return@launch
      val log = SessionLog(
        id = sessionId,
        startedAt = startedAt,
        mode = sessionMode.name,
        input = input.kind.name,
        utterances = done.map {
          LoggedUtterance(
            it.transcript, it.src?.code, it.tgt.code, it.detected, it.translation!!,
            speaker = it.speaker?.name,
            at = it.at,
          )
        },
      )
      withContext(persistDispatcher) { SessionStore.save(getApplication(), log) }
    }
  }

  /** Listen speaks only to a private output; Conversation engines speak through their own router. */
  private fun speak(text: String, tgt: Language) {
    val s = settings.value
    if (!s.autoSpeak || !listenHasPrivateOutput(mediaOutputTypes(getApplication()))) return
    val engine = tts ?: return
    val avail = engine.setLanguage(tgt.locale)
    if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
      status = SessionStatus.Engine(EngineStatus.NoTtsVoice(tgt))
      return
    }
    engine.setSpeechRate(s.speechRate)
    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "turn")
  }

  /**
   * Engines report status and completion from background threads. Those two touch the
   * service and the engine reference, so they hop to the main thread and are dropped if
   * [engine] is no longer current. Turn and partial updates only write snapshot state and
   * pass straight through.
   */
  private fun callbacksFor(engine: SpeechTranslator) = object : TranslatorCallbacks {
    override fun onTurnStart(turn: LiveTurn) { turns.add(turn) }

    override fun onTurnUpdate(id: Long, transcript: String?, translation: String?) {
      val idx = turns.indexOfFirst { it.id == id }
      if (idx < 0) return
      turns[idx] = turns[idx].copy(
        transcript = transcript ?: turns[idx].transcript,
        translation = translation ?: turns[idx].translation,
      )
    }

    override fun onTurnComplete(id: Long) = Unit

    override fun onPartial(text: String) { partial = text }

    override fun onStatus(status: EngineStatus) {
      viewModelScope.launch(Dispatchers.Main.immediate) {
        if (translator !== engine) return@launch
        if (status == EngineStatus.NoEarphones) noEarphones = true
        else this@NewSessionViewModel.status = SessionStatus.Engine(status)
      }
    }

    override fun onSpeak(text: String, tgt: Language) { speak(text, tgt) }
    override fun onPersist() { persist() }

    override fun onFinished(error: String?) {
      viewModelScope.launch(Dispatchers.Main.immediate) {
        if (translator !== engine) return@launch
        recording = false
        speaking = false
        partial = ""
        translator = null
        releaseService()
        status = error?.let { SessionStatus.Error(it) } ?: SessionStatus.Stopped(turns.size)
      }
    }
  }
}
