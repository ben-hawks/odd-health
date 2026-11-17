package dev.oddhealth.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.R
import dev.oddhealth.app.ui.components.BarChart
import dev.oddhealth.app.ui.components.BigValue
import dev.oddhealth.app.ui.components.HourRangeChart
import dev.oddhealth.app.ui.components.Hypnogram
import dev.oddhealth.app.ui.components.MetricCard
import dev.oddhealth.app.ui.components.Space
import dev.oddhealth.app.ui.components.Sparkline
import dev.oddhealth.app.ui.components.StatusRow
import dev.oddhealth.app.ui.components.formatDuration
import dev.oddhealth.app.ui.components.stageColor
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.model.SleepSummary
import dev.oddhealth.app.ui.model.StageKind
import dev.oddhealth.app.ui.model.TodayState
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodayScreen(
    state: TodayState,
    now: Instant,
    onOpen: (Metric) -> Unit,
    onRing: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.today)) },
                subtitle = { Text(state.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.m, end = Space.m, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Space.m,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusRow(state.ring, state.sync, now, onRing, onSync) }
            if (listOf(state.sleep, state.heartRate, state.hrv, state.spo2, state.activity, state.skin).all { it == null }) {
                item {
                    MetricCard(Icons.Rounded.Favorite, stringResource(R.string.no_data_yet)) {
                        Text(stringResource(R.string.no_data_yet_body), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            state.sleep?.let { sleep -> item { SleepCard(sleep) { onOpen(Metric.SLEEP) } } }
            state.heartRate?.let { hr ->
                item {
                    MetricCard(Icons.Rounded.Favorite, stringResource(R.string.heart_rate), onClick = { onOpen(Metric.HEART_RATE) }) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            BigValue((hr.latest ?: hr.avg).toString(), stringResource(R.string.unit_bpm))
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(R.string.range_today, hr.min, hr.max),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        HourRangeChart(hr.hours, description = stringResource(R.string.chart_hourly_range))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.hrv?.let { hrv ->
                        MetricCard(Icons.Rounded.MonitorHeart, stringResource(R.string.hrv), Modifier.weight(1f),
                            onClick = { onOpen(Metric.HRV) }) {
                            BigValue(hrv.avg.toString(), stringResource(R.string.unit_ms))
                            Sparkline(hrv.hours.map { it.avg })
                        }
                    }
                    state.spo2?.let { spo2 ->
                        MetricCard(Icons.Rounded.Air, stringResource(R.string.spo2), Modifier.weight(1f),
                            onClick = { onOpen(Metric.SPO2) }) {
                            BigValue(spo2.avg.toString(), "%")
                            Sparkline(spo2.hours.map { it.avg })
                        }
                    }
                }
            }
            state.activity?.let { act ->
                item {
                    MetricCard(Icons.Rounded.DirectionsWalk, stringResource(R.string.activity), onClick = { onOpen(Metric.ACTIVITY) }) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            BigValue(NumberFormat.getIntegerInstance().format(act.steps), stringResource(R.string.unit_steps))
                            Spacer(Modifier.weight(1f))
                            Text(
                                stringResource(R.string.kcal_line, act.totalKcal, act.activeKcal),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        BarChart(act.stepsByHour, description = stringResource(R.string.chart_steps_by_hour))
                    }
                }
            }
            state.skin?.let { skin ->
                item {
                    MetricCard(Icons.Rounded.Thermostat, stringResource(R.string.skin_temperature),
                        onClick = { onOpen(Metric.SKIN_TEMPERATURE) }) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            BigValue("%.1f".format(skin.celsius), "°C")
                            Spacer(Modifier.weight(1f))
                            Text(
                                skin.deltaFromBaseline?.let { stringResource(R.string.vs_baseline, "%+.1f".format(it)) }
                                    ?: stringResource(R.string.baseline_pending),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepCard(sleep: SleepSummary, onClick: () -> Unit) {
    MetricCard(Icons.Rounded.Bedtime, stringResource(R.string.sleep), onClick = onClick) {
        Row(verticalAlignment = Alignment.Bottom) {
            BigValue(formatDuration(sleep.asleep.seconds), null)
            Spacer(Modifier.weight(1f))
            sleep.score?.let { ScoreBadge(it) }
        }
        Text(
            "${sleep.bedtime} – ${sleep.wake}" + (sleep.efficiency?.let { " · " + stringResource(R.string.efficiency_short, it) } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Hypnogram(sleep.stages, description = stringResource(R.string.chart_hypnogram))
        StageLegend(sleep)
    }
}

@Composable
private fun ScoreBadge(score: Int) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(horizontal = Space.m, vertical = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(stringResource(R.string.score), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
fun StageLegend(sleep: SleepSummary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (kind in listOf(StageKind.DEEP, StageKind.LIGHT, StageKind.REM, StageKind.AWAKE)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(10.dp), shape = CircleShape, color = stageColor(kind)) {}
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(stringResource(stageLabel(kind)), style = MaterialTheme.typography.labelMedium)
                    Text(formatDuration(sleep.totals[kind]?.seconds ?: 0), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

fun stageLabel(kind: StageKind): Int = when (kind) {
    StageKind.DEEP -> R.string.stage_deep
    StageKind.LIGHT -> R.string.stage_light
    StageKind.REM -> R.string.stage_rem
    StageKind.AWAKE -> R.string.stage_awake
}
