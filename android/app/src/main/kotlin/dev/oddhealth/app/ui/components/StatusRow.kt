package dev.oddhealth.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.model.HealthConnectState
import dev.oddhealth.app.ui.model.RingStatus
import dev.oddhealth.app.ui.model.SyncStatus
import java.time.Duration
import java.time.Instant

/** Two compact tiles: ring connection and Health Connect sync. */
@Composable
fun StatusRow(ring: RingStatus, sync: SyncStatus, now: Instant, onRing: () -> Unit, onSync: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        val ringLine = when {
            !ring.connected -> stringResource(R.string.ring_not_connected)
            ring.batteryPercent != null -> stringResource(R.string.ring_battery, ring.batteryPercent)
            else -> stringResource(R.string.ring_connected)
        }
        StatusTile(
            icon = when {
                !ring.connected -> Icons.Rounded.BluetoothDisabled
                ring.charging -> Icons.Rounded.BatteryChargingFull
                else -> Icons.Rounded.Battery3Bar
            },
            title = stringResource(R.string.status_ring),
            line = ringLine,
            container = if (ring.connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
            content = if (ring.connected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            onClick = onRing,
            modifier = Modifier.weight(1f),
        )
        val ok = sync.state == HealthConnectState.READY
        StatusTile(
            icon = if (ok) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
            title = stringResource(R.string.health_connect),
            line = when {
                !ok -> stringResource(R.string.hc_action_needed)
                sync.pending > 0 -> stringResource(R.string.hc_pending, sync.pending)
                sync.lastSync != null -> stringResource(R.string.hc_synced_ago, ago(sync.lastSync, now))
                else -> stringResource(R.string.hc_not_synced)
            },
            container = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
            content = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            onClick = onSync,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatusTile(
    icon: ImageVector,
    title: String,
    line: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(horizontal = Space.m, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "4 min", "2 h", "3 d". */
fun ago(then: Instant, now: Instant): String {
    val d = Duration.between(then, now)
    return when {
        d.toMinutes() < 1 -> "<1 min"
        d.toHours() < 1 -> "${d.toMinutes()} min"
        d.toDays() < 1 -> "${d.toHours()} h"
        else -> "${d.toDays()} d"
    }
}
