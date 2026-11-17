package dev.oddhealth.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.oddhealth.app.ui.model.HourRange
import dev.oddhealth.app.ui.model.StageKind
import dev.oddhealth.app.ui.model.StageSpan

/** Colours for sleep stages, taken from the scheme so they follow dynamic colour. */
@Composable
fun stageColor(kind: StageKind): Color = when (kind) {
    StageKind.DEEP -> MaterialTheme.colorScheme.primary
    StageKind.LIGHT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    StageKind.REM -> MaterialTheme.colorScheme.tertiary
    StageKind.AWAKE -> MaterialTheme.colorScheme.outline
}

/** 24 columns; each hour is a rounded bar from min to max with a dot at the average. */
@Composable
fun HourRangeChart(
    hours: List<HourRange>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    description: String,
) {
    val range = MaterialTheme.colorScheme.primaryContainer
    val dot = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).semantics { contentDescription = description },
        ) {
            if (hours.isEmpty()) return@Canvas
            val lo = hours.minOf { it.min } - 4
            val hi = hours.maxOf { it.max } + 4
            fun y(v: Int) = size.height * (1f - (v - lo).toFloat() / (hi - lo))
            val col = size.width / 24f
            val bar = col * 0.56f
            for (i in 0..2) {
                val gy = size.height * i / 2f
                drawLine(grid, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            for (h in hours) {
                val cx = col * h.hour + col / 2
                val top = y(h.max)
                val bottom = y(h.min)
                drawRoundRect(range, Offset(cx - bar / 2, top), Size(bar, (bottom - top).coerceAtLeast(bar)),
                    CornerRadius(bar / 2, bar / 2))
                drawCircle(dot, radius = bar * 0.42f, center = Offset(cx, y(h.avg)))
            }
        }
        HourAxis()
    }
}

@Composable
fun HourAxis() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (label in listOf("00", "06", "12", "18", "24")) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Simple bars, one per value, e.g. steps per hour. */
@Composable
fun BarChart(values: List<Int>, modifier: Modifier = Modifier, height: Dp = 96.dp, description: String) {
    val color = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
            val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
            val col = size.width / values.size
            val bar = col * 0.6f
            values.forEachIndexed { i, v ->
                val h = if (v == 0) bar else (size.height * v / max).coerceAtLeast(bar)
                drawRoundRect(if (v == 0) empty else color, Offset(col * i + (col - bar) / 2, size.height - h),
                    Size(bar, h), CornerRadius(bar / 2, bar / 2))
            }
        }
        HourAxis()
    }
}

/** Sleep stages as stepped blocks, deep at the bottom. */
@Composable
fun Hypnogram(stages: List<StageSpan>, modifier: Modifier = Modifier, height: Dp = 112.dp, description: String) {
    val colors = StageKind.entries.associateWith { stageColor(it) }
    val rows = listOf(StageKind.AWAKE, StageKind.REM, StageKind.LIGHT, StageKind.DEEP)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val rowH = size.height / rows.size
        val block = rowH * 0.7f
        for (s in stages) {
            val row = rows.indexOf(s.kind)
            val x = s.from * size.width
            val w = ((s.to - s.from) * size.width).coerceAtLeast(2f)
            drawRoundRect(colors.getValue(s.kind), Offset(x, row * rowH + (rowH - block) / 2), Size(w, block),
                CornerRadius(block / 3, block / 3))
        }
    }
}

/** A thin line through hourly averages, for the small cards. */
@Composable
fun Sparkline(values: List<Int>, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val lo = values.min() - 1
        val hi = values.max() + 1
        val step = size.width / (values.size - 1)
        var prev: Offset? = null
        values.forEachIndexed { i, v ->
            val p = Offset(step * i, size.height * (1f - (v - lo).toFloat() / (hi - lo)))
            prev?.let { drawLine(color, it, p, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round) }
            prev = p
        }
    }
}
