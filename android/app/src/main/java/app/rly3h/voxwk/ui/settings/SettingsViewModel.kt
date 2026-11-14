package app.rly3h.voxwk.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rly3h.voxwk.BuildConfig
import app.rly3h.voxwk.R
import app.rly3h.voxwk.RecordingService
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.data.EndpointParams
import app.rly3h.voxwk.data.Models
import app.rly3h.voxwk.data.MtModel
import app.rly3h.voxwk.data.SettingsRepository
import app.rly3h.voxwk.data.VadParams
import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.OnlineProvider
import app.rly3h.voxwk.lang.onlineCode
import app.rly3h.voxwk.translate.GeminiLive
import app.rly3h.voxwk.translate.OpenAiRealtime
import app.rly3h.voxwk.translate.asrEnginesFor
import app.rly3h.voxwk.translate.releaseUnusedEngines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import uniffi.translatecore.mtSystemInfo
import uniffi.translatecore.mtUnload

/** CPU capability and ggml's feature line, queried once off the main thread. */
data class MtInfo(val supported: Boolean, val systemInfo: String)

/** Outcome of the "Test connection" button; null in the ViewModel means no test has been run. */
sealed interface KeyTest {
  data object Running : KeyTest
  data object Ok : KeyTest
  data class Failed(val detail: String) : KeyTest
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
  private val repository = SettingsRepository(app)

  val settings: StateFlow<AppSettings> =
    repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

  /** Whether a key is stored for the selected provider. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val hasKey: StateFlow<Boolean> = settings.map { it.onlineProvider }.distinctUntilChanged()
    .flatMapLatest { repository.hasApiKey(it) }
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  /** Null until the device has been queried. mtSystemInfo() initializes the llama backend, so it runs on IO. */
  var mtInfo by mutableStateOf<MtInfo?>(null)
    private set

  /** The 2B model can only be chosen once downloaded. */
  var mt2bPresent by mutableStateOf(false)
    private set

  /** The key field is never pre-filled with the stored secret; it resets when the provider changes. */
  var apiKeyInput by mutableStateOf("")
    private set
  var keyVisible by mutableStateOf(false)
    private set
  var keyTest by mutableStateOf<KeyTest?>(null)
    private set

  /** Not Compose state: read fresh on every composition. */
  val recording: Boolean get() = RecordingService.onStopRequested != null

  val versionName: String = BuildConfig.VERSION_NAME
  val gitHash: String = BuildConfig.GIT_HASH

  init {
    viewModelScope.launch {
      mtInfo = withContext(Dispatchers.IO) { MtInfo(mtSupported(), mtSystemInfo()) }
    }
    refreshModels()
  }

  /** Re-reads which models are on disk; downloads can finish while the screen is away. */
  fun refreshModels() {
    viewModelScope.launch {
      mt2bPresent = withContext(Dispatchers.IO) { Models.isPresent(getApplication(), MtModel.PLAMO3_2B.modelId) }
    }
  }

  fun onMyLanguageChange(value: Language) {
    // A listen source or partner equal to the new language reads back as Auto / the default.
    viewModelScope.launch { repository.update { it.copy(myLanguage = value) } }
  }

  /** Persist first, then free the engines the new choice no longer uses. */
  fun onJaRecognizerChange(value: JaRecognizer) {
    if (value == settings.value.jaRecognizer) return
    viewModelScope.launch {
      repository.update { it.copy(jaRecognizer = value) }
      val s = repository.settings.first()
      withContext(Dispatchers.IO) {
        releaseUnusedEngines(asrEnginesFor(s.sessionMode, s.listenSource, s.myLanguage, s.conversationPartner, s.jaRecognizer))
      }
    }
  }

  fun onMtModelChange(value: MtModel) {
    if (value == settings.value.mtModel) return
    viewModelScope.launch {
      repository.update { it.copy(mtModel = value) }
      withContext(Dispatchers.IO) { mtUnload() }
    }
  }

  fun onSpeechRateChange(value: Float) {
    viewModelScope.launch { repository.update { it.copy(speechRate = value) } }
  }

  fun onAutoSpeakChange(value: Boolean) {
    viewModelScope.launch { repository.update { it.copy(autoSpeak = value) } }
  }

  fun onVadChange(transform: (VadParams) -> VadParams) {
    viewModelScope.launch { repository.update { it.copy(vad = transform(it.vad)) } }
  }

  fun resetVad() = onVadChange { VadParams() }

  fun onEndpointChange(transform: (EndpointParams) -> EndpointParams) {
    viewModelScope.launch { repository.update { it.copy(endpoint = transform(it.endpoint)) } }
  }

  fun resetEndpoint() = onEndpointChange { EndpointParams() }

  fun onIdleGapChange(ms: Int) {
    viewModelScope.launch { repository.update { it.copy(onlineIdleGapMs = ms) } }
  }

  fun onProviderChange(value: OnlineProvider) {
    apiKeyInput = ""
    keyVisible = false
    keyTest = null
    viewModelScope.launch { repository.update { it.copy(onlineProvider = value) } }
  }

  fun onApiKeyInputChange(value: String) {
    apiKeyInput = value
  }

  fun toggleKeyVisible() {
    keyVisible = !keyVisible
  }

  fun saveKey() {
    val provider = settings.value.onlineProvider
    val key = apiKeyInput.trim()
    apiKeyInput = ""
    keyVisible = false
    keyTest = null
    // Tink/Keystore encryption blocks.
    viewModelScope.launch(Dispatchers.IO) { repository.setApiKey(provider, key) }
  }

  fun clearKey() {
    val provider = settings.value.onlineProvider
    keyTest = null
    viewModelScope.launch(Dispatchers.IO) { repository.setApiKey(provider, null) }
  }

  /** A successful call means the key and the network both work. */
  fun testConnection() {
    val s = settings.value
    keyTest = KeyTest.Running
    viewModelScope.launch {
      keyTest = try {
        withContext(Dispatchers.IO) {
          val key = repository.apiKey(s.onlineProvider) ?: error(getApplication<Application>().getString(R.string.settings_key_unreadable))
          when (s.onlineProvider) {
            OnlineProvider.OPENAI -> {
              val code = onlineCode(OnlineProvider.OPENAI, s.myLanguage) ?: error("No OpenAI code for ${s.myLanguage.code}")
              OpenAiRealtime.mintEphemeral(key, code)
            }
            OnlineProvider.GEMINI -> GeminiLive.testKey(key)
          }
        }
        KeyTest.Ok
      } catch (e: Exception) {
        KeyTest.Failed(e.message ?: getApplication<Application>().getString(R.string.status_unknown_detail))
      }
    }
  }
}
