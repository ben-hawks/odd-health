package dev.oddhealth.app.ui.update

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.components.ListGroup
import dev.oddhealth.app.ui.components.Space
import dev.oddhealth.app.ui.components.ago
import dev.oddhealth.app.update.UpdateState
import java.time.Instant

data class UpdateScreenState(
    val current: String,
    val state: UpdateState,
    val notifications: Boolean,
    val lastCheck: Instant?,
    /** False on debug (test-key) builds, which a release can't update. */
    val canInstall: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(
    state: UpdateScreenState,
    now: Instant,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onNotifications: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val s = state.state
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.updates_title)) },
                subtitle = { Text(stringResource(R.string.about_version, state.current)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Space.m, end = Space.m, top = padding.calculateTopPadding(), bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val available = s is UpdateState.Available || s is UpdateState.Downloading || s is UpdateState.Installing ||
                    (s is UpdateState.Failed && s.release != null)
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = if (available) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (available) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        Text(
                            when (s) {
                                UpdateState.Idle -> stringResource(R.string.update_idle)
                                UpdateState.Checking -> stringResource(R.string.update_checking)
                                is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                                is UpdateState.Available -> stringResource(R.string.update_available, s.release.version)
                                is UpdateState.Downloading -> stringResource(R.string.update_downloading, s.release.version)
                                is UpdateState.Installing -> stringResource(R.string.update_installing, s.release.version)
                                is UpdateState.Failed -> stringResource(R.string.update_failed, s.message)
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        state.lastCheck?.let {
                            Text(stringResource(R.string.update_last_check, ago(it, now)), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (s is UpdateState.Downloading) {
                            LinearWavyProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        val release = when (s) {
                            is UpdateState.Available -> s.release
                            is UpdateState.Failed -> s.release
                            else -> null
                        }
                        release?.notes?.takeIf { it.isNotBlank() }?.let {
                            Text(it.take(1200), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (!state.canInstall && release != null) {
                            Text(stringResource(R.string.update_debug_build), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            val busy = s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing
                            if (release != null) {
                                Button(onClick = onInstall, enabled = !busy && state.canInstall, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text(stringResource(R.string.update_install))
                                }
                            }
                            OutlinedButton(onClick = onCheck, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.update_check))
                            }
                        }
                    }
                }
            }
            item {
                ListGroup(
                    items = listOf({
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.update_notify_title)) },
                            supportingContent = { Text(stringResource(R.string.update_notify_body)) },
                            trailingContent = { Switch(checked = state.notifications, onCheckedChange = onNotifications) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }),
                )
            }
            item {
                Text(
                    stringResource(R.string.update_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.m),
                )
            }
        }
    }
}
