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
import dev.oddhealth.app.ui.about.AboutScreen
import dev.oddhealth.app.ui.about.FirstRunNotice
import dev.oddhealth.app.ui.debug.DebugScreen
import dev.oddhealth.app.ui.report.ReportScreen
import dev.oddhealth.app.ui.update.UpdateScreen
import dev.oddhealth.app.ui.update.UpdateScreenState
import dev.oddhealth.app.update.UpdateState
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
    data object About : Dest
    data object Updates : Dest
    data object Report : Dest
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
    val refresh: () -> Unit = {},
    val setHrPoll: (Int) -> Unit = {},
    val battery: () -> Unit = {},
    val syncNow: () -> Unit = {},
    val permissions: () -> Unit = {},
    val importExport: () -> Unit = {},
    val setDebugLogging: (Boolean) -> Unit = {},
    val shareDebugLogs: () -> Unit = {},
    val deleteDebugLogs: () -> Unit = {},
    val acceptNotice: () -> Unit = {},
    val checkUpdate: () -> Unit = {},
    val installUpdate: () -> Unit = {},
    val setUpdateNotifications: (Boolean) -> Unit = {},
    val reportBug: (Boolean) -> Unit = {},
)

@Composable
fun AppShell(
    today: TodayState?,
    ring: RingScreenState?,
    sync: SyncScreenState?,
    debug: DebugState?,
    updates: UpdateScreenState? = null,
    detail: (Metric, LocalDate) -> Flow<MetricDetailState>,
    now: Instant,
    actions: AppActions,
    importing: Boolean = false,
    refreshing: Boolean = false,
    version: String = "",
    noticeAccepted: Boolean = true,
    start: Dest = Dest.Today,
) {
    // One back stack: a tab root, optionally with screens on top.
    val backStack = remember { mutableStateListOf(start) }
    val root = backStack.first()
    if (!noticeAccepted && backStack.last() != Dest.About) {
        FirstRunNotice(onAccept = actions.acceptNotice, onMore = { backStack.add(Dest.About) })
    }
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
                            refreshing = refreshing,
                            onRefresh = actions.refresh,
                        )
                    }
                }
                entry<Dest.Ring> {
                    ring?.let { RingScreen(it, now, actions.requestData, actions.setListening, actions.setHrPoll, onDebug = { backStack.add(Dest.Debug) }, onBattery = actions.battery,
                        refreshing = refreshing, onRefresh = actions.refresh) }
                }
                entry<Dest.Sync> {
                    sync?.let { SyncScreen(it, now, actions.syncNow, actions.permissions, actions.importExport, importing,
                        refreshing = refreshing, onRefresh = actions.refresh, onAbout = { backStack.add(Dest.About) },
                        onUpdates = { backStack.add(Dest.Updates) },
                        onReport = { backStack.add(Dest.Report) },
                        updateAvailable = updates?.state.let { it is UpdateState.Available }) }
                }
                entry<Dest.Report> {
                    debug?.let {
                        ReportScreen(it, onBack = { backStack.removeAt(backStack.lastIndex) },
                            onOpenDebug = { backStack.add(Dest.Debug) }, onReport = actions.reportBug)
                    }
                }
                entry<Dest.Updates> {
                    updates?.let {
                        UpdateScreen(it, now, onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else goTo(Dest.Sync) },
                            actions.checkUpdate, actions.installUpdate, actions.setUpdateNotifications)
                    }
                }
                entry<Dest.About> {
                    AboutScreen(version, onBack = if (backStack.size > 1) ({ backStack.removeAt(backStack.lastIndex) }) else null)
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
