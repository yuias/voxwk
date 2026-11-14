package app.rly3h.voxwk

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class VoxwkApp : Application() {
  /** Lives as long as the process; for work that must outlive any screen or ViewModel. */
  val appScope = CoroutineScope(SupervisorJob())
}
