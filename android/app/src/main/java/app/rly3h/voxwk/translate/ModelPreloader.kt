package app.rly3h.voxwk.translate

import android.content.Context
import app.rly3h.voxwk.data.AppSettings
import app.rly3h.voxwk.data.Models
import app.rly3h.voxwk.data.requiredModelIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.translatecore.asrLoad
import uniffi.translatecore.asrStreamLoad
import uniffi.translatecore.mtLoad
import uniffi.translatecore.mtSupported
import uniffi.translatecore.vadLoad

sealed interface PreloadState {
  /** Nothing to load: the models the settings need are missing, or the device cannot run them. */
  data object Idle : PreloadState

  data class Loading(val done: Int, val total: Int) : PreloadState

  data object Ready : PreloadState

  data class Failed(val detail: String?) : PreloadState
}

/**
 * What to load and what to unload to go from the engines in memory ([loaded]) to the ones a
 * session needs ([needed], in load order). Only models that are [present] on disk can be loaded.
 */
internal fun preloadPlan(
  needed: List<String>,
  present: Set<String>,
  loaded: Set<String>,
): Pair<List<String>, List<String>> =
  needed.filter { it in present && it !in loaded } to loaded.filter { it !in needed }

/**
 * Keeps the engines the current settings need resident in the background, so the first session
 * does not wait for the weights to load. Reloads when the language, recognizer or MT-model
 * settings change and frees the engines that are no longer needed. Lives on the app scope.
 */
internal class ModelPreloader(
  private val context: Context,
  settings: Flow<AppSettings>,
  scope: CoroutineScope,
) {
  private val _state = MutableStateFlow<PreloadState>(PreloadState.Idle)
  val state: StateFlow<PreloadState> = _state.asStateFlow()

  // Model ids this preloader has loaded. Only the collector below touches it, and collectLatest
  // finishes the previous run before it starts the next, so runs never overlap.
  private var loaded = emptySet<String>()

  private val refreshes = MutableStateFlow(0)

  init {
    scope.launch {
      var seen = 0
      combine(settings.distinctUntilChangedBy { neededModels(it) }, refreshes) { s, n -> s to n }
        .collectLatest { (s, n) ->
          if (n != seen) {
            seen = n
            loaded = emptySet()
          }
          sync(s)
        }
    }
  }

  /**
   * Syncs again after a model was downloaded or deleted. Forgets what is loaded: a delete frees
   * an engine without telling this class, and the native loads are no-ops for engines that are
   * still resident.
   */
  fun refresh() = refreshes.update { it + 1 }

  private fun neededModels(s: AppSettings): List<String> = requiredModelIds(
    asrEnginesFor(s.sessionMode, s.listenSource, s.myLanguage, s.conversationPartner, s.jaRecognizer),
    s.mtModel,
  )

  // The native loads cannot be interrupted, so a run is cancelled only between two models.
  private suspend fun sync(s: AppSettings) = withContext(Dispatchers.IO) {
    val needed = neededModels(s)
    val mtOk = mtSupported()
    val present = needed.filterTo(HashSet()) { Models.isPresent(context, it) && (mtOk || !it.startsWith("mt_")) }
    val (load, unload) = preloadPlan(needed, present, loaded)
    // Unload first so a switch to a bigger model does not hold both in memory.
    for (id in unload) unloadEngineFor(id)
    loaded -= unload.toSet()
    try {
      load.forEachIndexed { i, id ->
        ensureActive()
        _state.value = PreloadState.Loading(i, load.size)
        loadEngine(id, s)
        loaded += id
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      _state.value = PreloadState.Failed(e.message)
      return@withContext
    }
    _state.value = if (needed.all { it in loaded }) PreloadState.Ready else PreloadState.Idle
  }

  /** Same arguments as the engines pass at session start, so their own load is then a no-op. */
  private fun loadEngine(id: String, s: AppSettings) {
    when (id) {
      "vad" -> vadLoad(
        Models.dir(context, id).absolutePath,
        s.vad.threshold,
        s.vad.minSilenceMs / 1000f,
        s.vad.minSpeechMs / 1000f,
        s.vad.maxSpeechMs / 1000f,
      )
      "asr" -> asrLoad(Models.dir(context, id).absolutePath)
      "asr_stream" -> asrStreamLoad(
        Models.dir(context, id).absolutePath,
        s.endpoint.rule1,
        s.endpoint.rule2,
        s.endpoint.rule3,
        "auto",
      )
      else -> mtLoad(modelFile(context, id).absolutePath)
    }
  }
}
