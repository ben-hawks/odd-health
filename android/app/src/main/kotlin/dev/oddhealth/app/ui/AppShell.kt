package dev.oddhealth.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.TripOrigin
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.oddhealth.app.ui.debug.DebugScreen
import dev.oddhealth.app.ui.model.DebugState
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.detail.MetricDetailScreen
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.model.MetricDetailState
import dev.oddhealth.app.ui.model.RingScreenState
import dev.oddhealth.app.ui.model.SyncScreenState
import dev.oddhealth.app.ui.model.TodayState
import dev.oddhealth.app.ui.ring.RingScreen
import dev.oddhealth.app.ui.sync.SyncScreen
import dev.oddhealth.app.ui.today.TodayScreen
import java.time.Instant

/** Navigation destinations. */
sealed interface Dest {
    data object Today : Dest
    data object Ring : Dest
    data object Sync : Dest
    data class Detail(val metric: Metric) : Dest
    data object Debug : Dest
}

private enum class Tab(val dest: Dest, val label: Int, val selected: ImageVector, val unselected: ImageVector) {
    TODAY(Dest.Today, R.string.today, Icons.Rounded.Today, Icons.Outlined.Today),
    RING(Dest.Ring, R.string.status_ring, Icons.Rounded.TripOrigin, Icons.Outlined.TripOrigin),
    SYNC(Dest.Sync, R.string.sync, Icons.Rounded.CloudSync, Icons.Outlined.CloudSync),
}

/** User actions the shell forwards; the activity supplies them. */
class AppActions(
    val requestData: () -> Unit = {},
    val setListening: (Boolean) -> Unit = {},
    val syncNow: () -> Unit = {},
    val permissions: () -> Unit = {},
    val importExport: () -> Unit = {},
    val setDebugLogging: (Boolean) -> Unit = {},
    val shareDebugLogs: () -> Unit = {},
    val deleteDebugLogs: () -> Unit = {},
)

@Composable
fun AppShell(
    today: TodayState?,
    ring: RingScreenState?,
    sync: SyncScreenState?,
    debug: DebugState?,
    detail: (Metric, LocalDate) -> Flow<MetricDetailState>,
    now: Instant,
    actions: AppActions,
    importing: Boolean = false,
    start: Dest = Dest.Today,
) {
    // One back stack: a tab root, optionally with screens on top.
    val backStack = remember { mutableStateListOf(start) }
    val root = backStack.first()
    fun goTo(tab: Dest) {
        backStack.clear()
        backStack.add(tab)
    }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            for (tab in Tab.entries) {
                val selected = root == tab.dest
                item(
                    selected = selected,
                    onClick = { goTo(tab.dest) },
                    icon = { Icon(if (selected) tab.selected else tab.unselected, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
    ) {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryProvider = entryProvider {
                entry<Dest.Today> {
                    today?.let {
                        TodayScreen(
                            it, now,
                            onOpen = { m -> backStack.add(Dest.Detail(m)) },
                            onRing = { goTo(Dest.Ring) },
                            onSync = { goTo(Dest.Sync) },
                        )
                    }
                }
                entry<Dest.Ring> {
                    ring?.let { RingScreen(it, now, actions.requestData, actions.setListening, onDebug = { backStack.add(Dest.Debug) }) }
                }
                entry<Dest.Sync> {
                    sync?.let { SyncScreen(it, now, actions.syncNow, actions.permissions, actions.importExport, importing) }
                }
                entry<Dest.Debug> {
                    debug?.let {
                        DebugScreen(it, onBack = { backStack.removeAt(backStack.lastIndex) }, actions.setDebugLogging,
                            actions.shareDebugLogs, actions.deleteDebugLogs)
                    }
                }
                entry<Dest.Detail> { key ->
                    var date by rememberSaveable { mutableStateOf(LocalDate.now()) }
                    val flow = remember(key.metric, date) { detail(key.metric, date) }
                    val state by flow.collectAsStateWithLifecycle(initialValue = null)
                    state?.let {
                        MetricDetailScreen(
                            it,
                            onBack = { backStack.removeAt(backStack.lastIndex) },
                            onPreviousDay = { date = date.minusDays(1) },
                            onNextDay = if (date < LocalDate.now()) ({ date = date.plusDays(1) }) else null,
                        )
                    }
                }
            },
        )
    }
}
