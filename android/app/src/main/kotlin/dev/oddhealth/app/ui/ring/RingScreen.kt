package dev.oddhealth.app.ui.ring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.components.ListGroup
import dev.oddhealth.app.ui.components.SectionHeader
import dev.oddhealth.app.ui.components.RefreshBox
import dev.oddhealth.app.ui.components.Space
import dev.oddhealth.app.ui.components.ago
import dev.oddhealth.app.ui.model.RingScreenState
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RingScreen(
    state: RingScreenState,
    now: Instant,
    onRequestData: () -> Unit,
    onListeningChange: (Boolean) -> Unit,
    onHrPollChange: (Int) -> Unit,
    onDebug: () -> Unit,
    onBattery: () -> Unit = {},
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val ring = state.ring
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.status_ring)) }, scrollBehavior = scroll) },
    ) { padding ->
        RefreshBox(refreshing, onRefresh, padding.calculateTopPadding()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Space.m, end = Space.m, top = 0.dp, bottom = Space.l),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = MaterialShapes.Cookie9Sided.toShape(),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(72.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.TripOrigin, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
                                    }
                                }
                                Spacer(Modifier.width(Space.m))
                                Column {
                                    Text(ring.name, style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        when {
                                            !ring.connected -> stringResource(R.string.ring_not_connected)
                                            ring.worn == true -> stringResource(R.string.ring_connected_worn)
                                            else -> stringResource(R.string.ring_connected)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            ring.batteryPercent?.let { pct ->
                                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    Row {
                                        Text(stringResource(R.string.battery), style = MaterialTheme.typography.labelLarge)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            if (ring.charging) stringResource(R.string.battery_charging, pct) else "$pct%",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                    LinearWavyProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                            Button(onClick = onRequestData, enabled = ring.connected, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.request_data))
                            }
                        }
                    }
                }
                item {
                    ListGroup(
                        items = listOf(
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.listen_title)) },
                                    supportingContent = { Text(stringResource(R.string.listen_body)) },
                                    trailingContent = { Switch(checked = state.listening, onCheckedChange = onListeningChange) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.hr_poll_title)) },
                                    supportingContent = {
                                        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                            Text(stringResource(R.string.hr_poll_body))
                                            val options = listOf(0 to R.string.off, 5 to R.string.every_5_min, 1 to R.string.every_min)
                                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                                options.forEachIndexed { i, (minutes, label) ->
                                                    SegmentedButton(
                                                        selected = state.hrPollMinutes == minutes,
                                                        onClick = { onHrPollChange(minutes) },
                                                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                                    ) { Text(stringResource(label)) }
                                                }
                                            }
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.battery_usage)) },
                                    supportingContent = {
                                        Text(
                                            when (state.batteryUnrestricted) {
                                                true -> stringResource(R.string.battery_unrestricted)
                                                false -> stringResource(R.string.battery_optimised)
                                                null -> ""
                                            },
                                        )
                                    },
                                    trailingContent = {
                                        if (state.batteryUnrestricted == false) {
                                            Icon(Icons.Rounded.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        } else {
                                            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                        }
                                    },
                                    modifier = Modifier.clickable(onClick = onBattery),
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.debug_logging)) },
                                    supportingContent = { Text(stringResource(if (state.debugLogging) R.string.on else R.string.off)) },
                                    trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                                    modifier = Modifier.clickable(onClick = onDebug),
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.firmware)) },
                                    supportingContent = { Text(ring.firmware ?: "—") },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            },
                            {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.last_data)) },
                                    supportingContent = {
                                        Text(ring.lastData?.let { stringResource(R.string.ago, ago(it, now)) } ?: "—")
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            },
                        ),
                    )
                }
                if (state.events.isNotEmpty()) item { SectionHeader(stringResource(R.string.recent_activity)) }
                if (state.events.isNotEmpty()) item {
                    ListGroup(
                        items = state.events.map { e ->
                            @Composable {
                                ListItem(
                                    headlineContent = { Text(e.text, style = MaterialTheme.typography.bodyMedium) },
                                    leadingContent = {
                                        Text(e.time.toString(), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
