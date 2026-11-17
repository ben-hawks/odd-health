package dev.oddhealth.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.model.SyncMark

/** Standard spacing (8dp grid). */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
}

/** A tappable card for one metric: icon + title row, a big value, then custom content. */
@Composable
fun MetricCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors, content = body)
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors, content = body)
    }
}

/** Big number with a smaller unit after it. */
@Composable
fun BigValue(value: String, unit: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
        if (unit != null) {
            Spacer(Modifier.width(Space.xs))
            Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
fun SyncMarkIcon(mark: SyncMark, modifier: Modifier = Modifier) {
    val (icon, label, tint) = when (mark) {
        SyncMark.SYNCED -> Triple(Icons.Rounded.CloudDone, R.string.mark_synced, MaterialTheme.colorScheme.primary)
        SyncMark.PENDING -> Triple(Icons.Rounded.CloudUpload, R.string.mark_pending, MaterialTheme.colorScheme.onSurfaceVariant)
        SyncMark.APP_ONLY -> Triple(Icons.Rounded.PhoneAndroid, R.string.mark_app_only, MaterialTheme.colorScheme.tertiary)
    }
    Icon(icon, contentDescription = stringResource(label), tint = tint, modifier = modifier.size(20.dp))
}

/** Hours and minutes, e.g. 4h 08m. */
fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
}
