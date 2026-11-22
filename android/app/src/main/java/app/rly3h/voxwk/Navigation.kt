package app.rly3h.voxwk

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.rly3h.voxwk.ui.history.HistoryDetailScreen
import app.rly3h.voxwk.ui.history.SessionHistoryScreen
import app.rly3h.voxwk.ui.session.NewSessionScreen
import app.rly3h.voxwk.ui.settings.SettingsPageScreen
import app.rly3h.voxwk.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(NewSession)
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()

  // Top-level destinations are roots: replace the stack so Back exits cleanly.
  fun navigateTop(key: NavKey) {
    backStack.clear()
    backStack.add(key)
    scope.launch { drawerState.close() }
  }

  val current = backStack.lastOrNull()

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet {
        Text(
          stringResource(R.string.app_name),
          modifier = Modifier.padding(16.dp),
          style = MaterialTheme.typography.titleLarge,
        )
        HorizontalDivider()
        NavigationDrawerItem(
          label = { Text(stringResource(R.string.nav_new_session)) },
          selected = current == NewSession,
          onClick = { navigateTop(NewSession) },
          modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
          label = { Text(stringResource(R.string.nav_history)) },
          selected = current == SessionHistory || current is HistoryDetail,
          onClick = { navigateTop(SessionHistory) },
          modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
          label = { Text(stringResource(R.string.nav_settings)) },
          selected = current == SettingsKey || current is SettingsDetail,
          onClick = { navigateTop(SettingsKey) },
          modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
      }
    },
  ) {
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<NewSession> {
            AppBarScaffold(stringResource(R.string.nav_new_session), onMenu = { scope.launch { drawerState.open() } }) {
              NewSessionScreen(it)
            }
          }
          entry<SessionHistory> {
            AppBarScaffold(stringResource(R.string.nav_history), onMenu = { scope.launch { drawerState.open() } }) {
              SessionHistoryScreen(onOpen = { id -> backStack.add(HistoryDetail(id)) }, modifier = it)
            }
          }
          entry<SettingsKey> {
            AppBarScaffold(stringResource(R.string.nav_settings), onMenu = { scope.launch { drawerState.open() } }) {
              SettingsScreen(onOpen = { page -> backStack.add(SettingsDetail(page)) }, modifier = it)
            }
          }
          entry<SettingsDetail> { key ->
            AppBarScaffold(stringResource(key.page.title), onBack = { backStack.removeLastOrNull() }) {
              SettingsPageScreen(key.page, it)
            }
          }
          entry<HistoryDetail> { key ->
            AppBarScaffold(stringResource(R.string.nav_session), onBack = { backStack.removeLastOrNull() }) {
              HistoryDetailScreen(key.id, it)
            }
          }
        },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBarScaffold(
  title: String,
  onMenu: (() -> Unit)? = null,
  onBack: (() -> Unit)? = null,
  content: @Composable (Modifier) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          when {
            onBack != null ->
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
              }
            onMenu != null ->
              IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.nav_menu))
              }
          }
        },
      )
    },
  ) { padding -> content(Modifier.padding(padding)) }
}
