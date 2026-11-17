package dev.oddhealth.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.components.BarChart
import dev.oddhealth.app.ui.components.BigValue
import dev.oddhealth.app.ui.components.HourRangeChart
import dev.oddhealth.app.ui.components.Hypnogram
import dev.oddhealth.app.ui.components.ListGroup
import dev.oddhealth.app.ui.components.SectionHeader
import dev.oddhealth.app.ui.components.Space
import dev.oddhealth.app.ui.components.SyncMarkIcon
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.model.MetricDetailState
import dev.oddhealth.app.ui.model.SyncMark
import dev.oddhealth.app.ui.today.StageLegend
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun metricTitle(metric: Metric): Int = when (metric) {
    Metric.SLEEP -> R.string.sleep
    Metric.HEART_RATE -> R.string.heart_rate
    Metric.HRV -> R.string.hrv
    Metric.SPO2 -> R.string.spo2
    Metric.ACTIVITY -> R.string.activity
    Metric.SKIN_TEMPERATURE -> R.string.skin_temperature
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetricDetailScreen(
    state: MetricDetailState,
    onBack: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(metricTitle(state.metric))) },
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = onPreviousDay) {
                        Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.previous_day))
                    }
                    Text(
                        state.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(onClick = { onNextDay?.invoke() }, enabled = onNextDay != null) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.next_day))
                    }
                }
            }
            item {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        BigValue(state.headline, state.headlineUnit)
                        Text(state.caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.vital?.let { HourRangeChart(it.hours, height = 180.dp, description = stringResource(R.string.chart_hourly_range)) }
                        state.activity?.let { BarChart(it.stepsByHour, height = 160.dp, description = stringResource(R.string.chart_steps_by_hour)) }
                        state.sleep?.let {
                            Hypnogram(it.stages, height = 160.dp, description = stringResource(R.string.chart_hypnogram))
                            StageLegend(it)
                        }
                    }
                }
            }
            state.appOnlyNote?.let { note ->
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    ) {
                        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.values)) }
            item {
                ListGroup(
                    items = state.rows.map { row ->
                        @Composable {
                            ListItem(
                                headlineContent = { Text(row.value) },
                                overlineContent = { Text(row.time) },
                                supportingContent = row.detail?.let { { Text(it) } },
                                trailingContent = { SyncMarkIcon(row.mark) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    },
                )
            }
            item { MarkLegend() }
        }
    }
}

@Composable
private fun MarkLegend() {
    Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((mark, label) in listOf(
            SyncMark.SYNCED to R.string.mark_synced,
            SyncMark.PENDING to R.string.mark_pending,
            SyncMark.APP_ONLY to R.string.mark_app_only,
        )) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SyncMarkIcon(mark, Modifier.size(16.dp))
                Spacer(Modifier.width(Space.xs))
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
