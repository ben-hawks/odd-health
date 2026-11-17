package dev.oddhealth.app.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import dev.oddhealth.app.ui.components.SyncMarkIcon
import dev.oddhealth.app.ui.components.ago
import dev.oddhealth.app.ui.model.HealthConnectState
import dev.oddhealth.app.ui.model.SyncMark
import dev.oddhealth.app.ui.model.SyncScreenState
import dev.oddhealth.app.ui.model.TypeStatus
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SyncScreen(
    state: SyncScreenState,
    now: Instant,
    onSyncNow: () -> Unit,
    onPermissions: () -> Unit,
    onImport: () -> Unit,
    importing: Boolean = false,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val ready = state.sync.state == HealthConnectState.READY
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text(stringResource(R.string.sync)) }, scrollBehavior = scroll) },
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
                        colors = CardDefaults.cardColors(
                            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            contentColor = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (ready) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff, contentDescription = null,
                                    modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(Space.m))
                                Column {
                                    Text(stringResource(R.string.health_connect), style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        when (state.sync.state) {
                                            HealthConnectState.READY -> state.sync.lastSync?.let {
                                                stringResource(R.string.hc_synced_ago, ago(it, now)) +
                                                    if (state.sync.pending > 0) " · " + stringResource(R.string.hc_pending, state.sync.pending) else ""
                                            } ?: stringResource(R.string.hc_not_synced)
                                            HealthConnectState.NEEDS_PERMISSION -> stringResource(R.string.hc_needs_permission)
                                            HealthConnectState.NOT_INSTALLED -> stringResource(R.string.hc_not_installed)
                                            HealthConnectState.UNSUPPORTED -> stringResource(R.string.hc_unsupported)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            if (state.sync.state == HealthConnectState.NEEDS_PERMISSION) {
                                Text(stringResource(R.string.hc_sideload_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                Button(onClick = onSyncNow, enabled = ready && !state.syncing, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text(stringResource(if (state.syncing) R.string.syncing else R.string.sync_now))
                                }
                                OutlinedButton(onClick = onPermissions, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.permissions))
                                }
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.what_is_synced)) }
                item {
                    ListGroup(
                        items = state.types.map { t ->
                            @Composable {
                                ListItem(
                                    headlineContent = { Text(t.title) },
                                    supportingContent = {
                                        Text(
                                            listOfNotNull(
                                                t.healthConnectType?.let { stringResource(R.string.as_type, it) }
                                                    ?: stringResource(R.string.shown_in_app_only),
                                                t.note,
                                            ).joinToString(" · "),
                                        )
                                    },
                                    trailingContent = {
                                        SyncMarkIcon(
                                            when (t.status) {
                                                TypeStatus.WRITTEN -> SyncMark.SYNCED
                                                TypeStatus.APP_ONLY -> SyncMark.APP_ONLY
                                                TypeStatus.NEEDS_PERMISSION -> SyncMark.PENDING
                                            },
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                )
                            }
                        },
                    )
                }
                item { SectionHeader(stringResource(R.string.stored_on_phone)) }
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            val total = state.stored.values.sum()
                            Text(
                                stringResource(R.string.records_stored, NumberFormat.getIntegerInstance().format(total)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            state.oldest?.let {
                                Text(
                                    stringResource(R.string.since, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = onImport, enabled = !importing) {
                                Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(if (importing) R.string.importing else R.string.import_export))
                            }
                        }
                    }
                }
            }
        }
    }
}
