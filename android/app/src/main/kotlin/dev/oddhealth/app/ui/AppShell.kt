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
import androidx.compose.runtime.remember
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
}

private enum class Tab(val dest: Dest, val label: Int, val selected: ImageVector, val unselected: ImageVector) {
    TODAY(Dest.Today, R.string.today, Icons.Rounded.Today, Icons.Outlined.Today),
    RING(Dest.Ring, R.string.status_ring, Icons.Rounded.TripOrigin, Icons.Outlined.TripOrigin),
    SYNC(Dest.Sync, R.string.sync, Icons.Rounded.CloudSync, Icons.Outlined.CloudSync),
}

/** Everything the shell needs; the activity supplies it from view models. */
class AppActions(
    val requestData: () -> Unit = {},
    val shareLog: () -> Unit = {},
    val setListening: (Boolean) -> Unit = {},
    val syncNow: () -> Unit = {},
    val permissions: () -> Unit = {},
    val importExport: () -> Unit = {},
)

@Composable
fun AppShell(
    today: TodayState,
    ring: RingScreenState,
    sync: SyncScreenState,
    detail: (Metric) -> MetricDetailState,
    now: Instant,
    actions: AppActions,
) {
    // One back stack: a tab root, optionally with a detail on top.
    val backStack = remember { mutableStateListOf<Dest>(Dest.Today) }
    val root = backStack.first()
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            for (tab in Tab.entries) {
                val selected = root == tab.dest
                item(
                    selected = selected,
                    onClick = {
                        backStack.clear()
                        backStack.add(tab.dest)
                    },
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
                    TodayScreen(
                        today, now,
                        onOpen = { backStack.add(Dest.Detail(it)) },
                        onRing = { backStack.clear(); backStack.add(Dest.Ring) },
                        onSync = { backStack.clear(); backStack.add(Dest.Sync) },
                    )
                }
                entry<Dest.Ring> {
                    RingScreen(ring, now, actions.requestData, actions.shareLog, actions.setListening)
                }
                entry<Dest.Sync> {
                    SyncScreen(sync, now, actions.syncNow, actions.permissions, actions.importExport)
                }
                entry<Dest.Detail> { key ->
                    MetricDetailScreen(
                        detail(key.metric),
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onPreviousDay = {},
                        onNextDay = null,
                    )
                }
            },
        )
    }
}
