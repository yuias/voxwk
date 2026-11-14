package app.rly3h.voxwk

import android.app.Application
import app.rly3h.voxwk.data.SettingsRepository
import app.rly3h.voxwk.translate.ModelPreloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class VoxwkApp : Application() {
  /** Lives as long as the process; for work that must outlive any screen or ViewModel. */
  val appScope = CoroutineScope(SupervisorJob())

  internal lateinit var preloader: ModelPreloader
    private set

  override fun onCreate() {
    super.onCreate()
    preloader = ModelPreloader(this, SettingsRepository(this).settings, appScope)
  }
}
