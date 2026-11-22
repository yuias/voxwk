package app.rly3h.voxwk.ui.settings

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import app.rly3h.voxwk.BuildConfig
import app.rly3h.voxwk.R
import app.rly3h.voxwk.RecordingService
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.data.EndpointParams
import app.rly3h.voxwk.data.GlassMic
import app.rly3h.voxwk.data.ModelDownloadWorker
import app.rly3h.voxwk.data.ModelSpec
import app.rly3h.voxwk.data.Models
import app.rly3h.voxwk.data.MtModel
import app.rly3h.voxwk.data.SettingsRepository
import app.rly3h.voxwk.data.VadParams
import app.rly3h.voxwk.data.requiredModelIds
import app.rly3h.voxwk.glass.GlassDisplayMode
import app.rly3h.voxwk.lang.JaRecognizer
import app.rly3h.voxwk.lang.Language
import app.rly3h.voxwk.lang.OnlineProvider
import app.rly3h.voxwk.lang.onlineCode
import app.rly3h.voxwk.translate.GeminiLive
import app.rly3h.voxwk.translate.OpenAiRealtime
import app.rly3h.voxwk.translate.asrEnginesFor
import app.rly3h.voxwk.translate.releaseUnusedEngines
import app.rly3h.voxwk.translate.unloadEngineFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

/** What the model list shows for the download of one model. */
sealed interface ModelDownload {
  data object Idle : ModelDownload

  /** Queued, or waiting for WorkManager to retry after a network error. */
  data object Waiting : ModelDownload

  /** [total] is -1 when unknown, and always for [Models.PHASE_EXTRACT]. */
  data class Running(val phase: String, val bytes: Long, val total: Long) : ModelDownload

  data class Failed(val detail: String) : ModelDownload
}

data class ModelRowState(
  val id: String,
  val name: String,
  val approxMb: Int?,
  val present: Boolean,
  /** Bytes under the model's directory, including a partial download. */
  val bytes: Long,
  /** Needed by the current settings (see requiredModelIds). */
  val inUse: Boolean,
  val download: ModelDownload,
) {
  val active: Boolean get() = download is ModelDownload.Waiting || download is ModelDownload.Running
}

data class ModelsUiState(val rows: List<ModelRowState> = emptyList()) {
  /** Models the current settings need that are neither present nor already downloading. */
  val missingRequired: List<String> get() = rows.filter { it.inUse && !it.present && !it.active }.map { it.id }
  val allRequiredPresent: Boolean get() = rows.none { it.inUse && !it.present }
}

/** A bonded Bluetooth device the Glass can be picked from; [label] is the name, else the address. */
data class GlassDevice(val address: String, val label: String)

private data class DiskState(val present: Boolean, val bytes: Long)

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

  private val specs = MutableStateFlow<List<ModelSpec>>(emptyList())
  private val disk = MutableStateFlow<Map<String, DiskState>>(emptyMap())

  @OptIn(ExperimentalCoroutinesApi::class)
  private val works: StateFlow<Map<String, WorkInfo?>> = specs.flatMapLatest { list ->
    if (list.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(list.map { spec -> Models.workInfo(app, spec.id).map { spec.id to it } }) { it.toMap() }
    }
  }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

  val models: StateFlow<ModelsUiState> = combine(specs, disk, works, settings) { specs, disk, works, s ->
    val required = requiredModelIds(asrEnginesFor(s.sessionMode, s.listenSource, s.myLanguage, s.conversationPartner, s.jaRecognizer), s.mtModel)
    ModelsUiState(
      specs.map { spec ->
        val state = disk[spec.id] ?: DiskState(false, 0L)
        ModelRowState(spec.id, spec.displayName, spec.approxMb, state.present, state.bytes, spec.id in required, downloadOf(works[spec.id], state.present))
      },
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, ModelsUiState())

  /** Model id awaiting the delete confirmation. */
  var pendingDelete by mutableStateOf<String?>(null)
    private set

  /** A delete is running; downloads and further deletes wait for it. */
  var deleting by mutableStateOf(false)
    private set

  /** Outcome of the last delete, shown under the list. */
  var modelMessage by mutableStateOf<String?>(null)
    private set

  /** The key field is never pre-filled with the stored secret; it resets when the provider changes. */
  var apiKeyInput by mutableStateOf("")
    private set
  var keyVisible by mutableStateOf(false)
    private set
  var keyTest by mutableStateOf<KeyTest?>(null)
    private set

  /** Bonded devices, empty while BLUETOOTH_CONNECT is not granted. */
  var glassDevices by mutableStateOf<List<GlassDevice>>(emptyList())
    private set
  var glassPermitted by mutableStateOf(false)
    private set

  /** Not Compose state: read fresh on every composition. */
  val recording: Boolean get() = RecordingService.onStopRequested != null

  val versionName: String = BuildConfig.VERSION_NAME
  val gitHash: String = BuildConfig.GIT_HASH

  init {
    viewModelScope.launch {
      mtInfo = withContext(Dispatchers.IO) { MtInfo(mtSupported(), mtSystemInfo()) }
    }
    viewModelScope.launch {
      specs.value = withContext(Dispatchers.IO) { Models.manifest(app).models }
      // A finished download changes what is on disk; progress ticks do not.
      works.map { all -> all.mapValues { it.value?.state } }.distinctUntilChanged().collect { readDisk() }
    }
  }

  private suspend fun readDisk() {
    val app = getApplication<Application>()
    val state = withContext(Dispatchers.IO) {
      specs.value.associate { it.id to DiskState(Models.isPresent(app, it.id), Models.sizeOnDisk(app, it.id)) }
    }
    disk.value = state
    mt2bPresent = state[MtModel.PLAMO3_2B.modelId]?.present == true
  }

  private fun downloadOf(info: WorkInfo?, present: Boolean): ModelDownload = when {
    info == null || present -> ModelDownload.Idle
    info.state == WorkInfo.State.RUNNING -> {
      val p = info.progress
      val phase = p.getString(ModelDownloadWorker.KEY_PHASE)
      if (phase == null) {
        ModelDownload.Waiting
      } else {
        ModelDownload.Running(phase, p.getLong(ModelDownloadWorker.KEY_BYTES, 0L), p.getLong(ModelDownloadWorker.KEY_TOTAL, -1L))
      }
    }
    !info.state.isFinished -> ModelDownload.Waiting
    info.state == WorkInfo.State.FAILED ->
      info.outputData.getString(ModelDownloadWorker.KEY_ERROR)?.let { ModelDownload.Failed(it) } ?: ModelDownload.Idle
    else -> ModelDownload.Idle
  }

  /** Re-reads which models are on disk; downloads can finish while the screen is away. */
  fun refreshModels() {
    viewModelScope.launch { readDisk() }
  }

  fun downloadModel(id: String) {
    modelMessage = null
    Models.enqueue(getApplication(), id)
  }

  fun downloadRequiredModels() {
    modelMessage = null
    models.value.missingRequired.forEach { Models.enqueue(getApplication(), it) }
  }

  /** Models.cancel deletes files, so it runs on IO. */
  fun cancelDownload(id: String) {
    viewModelScope.launch(Dispatchers.IO) { Models.cancel(getApplication(), id) }
  }

  fun requestDelete(id: String) {
    pendingDelete = id
  }

  fun dismissDelete() {
    pendingDelete = null
  }

  fun confirmDelete() {
    val id = pendingDelete ?: return
    pendingDelete = null
    val app = getApplication<Application>()
    // A session may have started while the dialog was open.
    if (recording) {
      modelMessage = app.getString(R.string.models_delete_while_recording)
      return
    }
    val name = specs.value.first { it.id == id }.displayName
    deleting = true
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) {
          // Release the engine first: the GGUF is mmapped and ONNX engines hold their files open.
          unloadEngineFor(id)
          Models.delete(app, id)
        }
        readDisk()
        // delete() is best-effort and does not throw, so check what is left on disk.
        modelMessage = app.getString(
          if ((disk.value[id]?.bytes ?: 0L) > 0) R.string.models_delete_incomplete else R.string.models_deleted,
          name,
        )
      } catch (e: Exception) {
        readDisk()
        modelMessage = app.getString(R.string.models_delete_failed, e.message ?: app.getString(R.string.status_unknown_detail))
      } finally {
        deleting = false
      }
    }
  }

  /** Called on resume and after the permission dialog: the user can pair or grant outside the app. */
  fun refreshGlassDevices() {
    val app = getApplication<Application>()
    glassPermitted = app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    glassDevices = if (glassPermitted) {
      app.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
        .map { GlassDevice(it.address, it.name ?: it.address) }
        .sortedBy { it.label }
    } else {
      emptyList()
    }
  }

  fun onGlassEnabledChange(value: Boolean) {
    viewModelScope.launch { repository.update { it.copy(glassEnabled = value) } }
  }

  fun onGlassAddressChange(value: String) {
    viewModelScope.launch { repository.update { it.copy(glassAddress = value) } }
  }

  fun onGlassMicChange(value: GlassMic) {
    viewModelScope.launch { repository.update { it.copy(glassMic = value) } }
  }

  fun onGlassDisplayChange(value: GlassDisplayMode) {
    viewModelScope.launch { repository.update { it.copy(glassDisplay = value) } }
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

  fun onAutoSpeakPartnerChange(value: Boolean) {
    viewModelScope.launch { repository.update { it.copy(autoSpeakPartner = value) } }
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
