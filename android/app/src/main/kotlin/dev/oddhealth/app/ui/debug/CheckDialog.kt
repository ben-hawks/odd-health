package dev.oddhealth.app.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ring.CheckResult
import dev.oddhealth.app.ui.components.Space
import dev.oddhealth.r1.CheckOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun outcomeColor(outcome: CheckOutcome?): Color = when (outcome) {
    CheckOutcome.PASS -> MaterialTheme.colorScheme.primary
    CheckOutcome.WARN -> MaterialTheme.colorScheme.tertiary
    CheckOutcome.FAIL -> MaterialTheme.colorScheme.error
    CheckOutcome.INFO, null -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun OutcomeIcon(outcome: CheckOutcome, modifier: Modifier = Modifier) {
    val (icon, label) = when (outcome) {
        CheckOutcome.PASS -> Icons.Rounded.CheckCircle to R.string.check_pass
        CheckOutcome.WARN -> Icons.Rounded.Warning to R.string.check_warn
        CheckOutcome.FAIL -> Icons.Rounded.Error to R.string.check_fail
        CheckOutcome.INFO -> Icons.Rounded.Info to R.string.check_info
    }
    Icon(icon, contentDescription = stringResource(label), tint = outcomeColor(outcome), modifier = modifier.size(20.dp))
}

private fun outcomeLabel(outcome: CheckOutcome?) = when (outcome) {
    CheckOutcome.PASS -> R.string.check_pass
    CheckOutcome.WARN -> R.string.check_warn
    CheckOutcome.FAIL -> R.string.check_fail
    CheckOutcome.INFO -> R.string.check_info
    null -> R.string.check_absent
}

fun formatCheckTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** The results popup: what changed since the last run, then every check. */
@Composable
fun CheckDialog(result: CheckResult, onDismiss: () -> Unit, onShare: () -> Unit) {
    val report = result.report
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checks_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.checks_summary, report.firmware ?: "?", formatCheckTime(report.startedAt),
                        report.count(CheckOutcome.PASS), report.count(CheckOutcome.WARN), report.count(CheckOutcome.FAIL),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val previous = result.previous
                if (previous != null) {
                    Text(
                        stringResource(R.string.checks_changes, previous.firmware ?: "?", formatCheckTime(previous.startedAt)),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (result.changes.isEmpty()) {
                        Text(stringResource(R.string.checks_no_changes), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    for (c in result.changes) {
                        Column {
                            Text(c.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(outcomeLabel(c.before)) + " → " + stringResource(outcomeLabel(c.after)) + " · " + c.detail,
                                style = MaterialTheme.typography.bodySmall, color = outcomeColor(c.after),
                            )
                        }
                    }
                    HorizontalDivider()
                }
                for (item in report.items) {
                    Row(verticalAlignment = Alignment.Top) {
                        OutcomeIcon(item.outcome, Modifier.padding(top = 2.dp))
                        Spacer(Modifier.width(Space.s))
                        Column {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(stringResource(R.string.checks_logged), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = onShare) { Text(stringResource(R.string.share)) } },
    )
}
