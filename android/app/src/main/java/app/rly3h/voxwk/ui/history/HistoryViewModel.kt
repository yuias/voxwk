package app.rly3h.voxwk.ui.history

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.rly3h.voxwk.data.SessionLog
import app.rly3h.voxwk.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reads saved sessions for the history list and the detail view; the disk work runs off the main thread. */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
  /** Newest first; null until the first load finishes, so the empty state does not flash. */
  var sessions by mutableStateOf<List<SessionLog>?>(null)
    private set

  /** The session opened by [loadDetail]; null while loading or when the id has no readable file. */
  var detail by mutableStateOf<SessionLog?>(null)
    private set

  /** True once [loadDetail] has finished, to tell "not found" from "still loading". */
  var detailLoaded by mutableStateOf(false)
    private set

  fun loadSessions() {
    viewModelScope.launch {
      sessions = withContext(Dispatchers.IO) { SessionStore.list(getApplication()) }
    }
  }

  fun delete(id: String) {
    viewModelScope.launch {
      withContext(Dispatchers.IO) { SessionStore.delete(getApplication(), id) }
      loadSessions()
    }
  }

  fun loadDetail(id: String) {
    detail = null
    detailLoaded = false
    viewModelScope.launch {
      detail = withContext(Dispatchers.IO) { SessionStore.load(getApplication(), id) }
      detailLoaded = true
    }
  }
}
