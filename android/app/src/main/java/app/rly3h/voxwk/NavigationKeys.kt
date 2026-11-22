package app.rly3h.voxwk

import androidx.navigation3.runtime.NavKey
import app.rly3h.voxwk.ui.settings.SettingsPage
import kotlinx.serialization.Serializable

@Serializable data object NewSession : NavKey

@Serializable data object SessionHistory : NavKey

@Serializable data object SettingsKey : NavKey

/** Detail view for one saved session, identified by its stored id. */
@Serializable data class HistoryDetail(val id: String) : NavKey

/** One settings page, opened from the Settings list. */
@Serializable data class SettingsDetail(val page: SettingsPage) : NavKey
