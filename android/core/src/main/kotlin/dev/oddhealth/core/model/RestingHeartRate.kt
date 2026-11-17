package dev.oddhealth.core.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Resting heart rate for one night. The ring does not report one, so this is
 * our estimate (see [RestingHeartRateEstimate]). [start] is the time the sleep
 * ended. [readings] is how many individual readings it came from; 0 means it
 * came from hourly averages.
 */
data class RestingHeartRate(
    override val start: Instant,
    override val zone: ZoneOffset,
    val bpm: Int,
    override val source: DataSource,
    override val version: Long,
    val readings: Int = 0,
) : HealthRecord {
    override val kind get() = RecordKind.RESTING_HEART_RATE
    override val end: Instant get() = start
}

object RestingHeartRateEstimate {
    /** Shorter sleeps (naps) don't give a resting heart rate. */
    val MIN_SLEEP: Duration = Duration.ofHours(3)

    /** Readings needed during the night to use them instead of hourly averages. */
    const val MIN_READINGS = 12

    /** Readings averaged together, and the most time they may span (no gaps). */
    const val WINDOW = 3
    val WINDOW_SPAN: Duration = Duration.ofMinutes(30)

    /**
     * With enough individual readings during the main sleep (from Frequent
     * updates, about every 10 minutes): the lowest average of [WINDOW]
     * consecutive readings within [WINDOW_SPAN]. Averaging a few readings keeps
     * one odd reading from setting the result.
     *
     * Otherwise: the lowest hourly average during the sleep, which reads higher
     * because each hour mixes calm and restless minutes.
     */
    fun forNight(
        night: SleepSession,
        heartRate: List<HourlyVital>,
        readings: List<HeartRateReading> = emptyList(),
    ): RestingHeartRate? {
        if (Duration.between(night.start, night.end) < MIN_SLEEP) return null
        fromReadings(night, readings)?.let { return it }
        val hours = heartRate.filter {
            it.kind == RecordKind.HEART_RATE_HOUR && it.avg > 0 &&
                overlapSeconds(it, night) >= 1800
        }
        val lowest = hours.minByOrNull { it.avg } ?: return null
        return RestingHeartRate(
            start = night.end,
            zone = night.zone,
            bpm = lowest.avg,
            source = night.source,
            version = maxOf(night.version, hours.maxOf { it.version }),
        )
    }

    private fun fromReadings(night: SleepSession, all: List<HeartRateReading>): RestingHeartRate? {
        val readings = all
            .filter { it.bpm > 0 && it.start >= night.start && it.start <= night.end }
            .distinctBy { it.start }
            .sortedBy { it.start }
        if (readings.size < MIN_READINGS) return null
        val lowest = readings.windowed(WINDOW)
            .filter { Duration.between(it.first().start, it.last().start) <= WINDOW_SPAN }
            .minOfOrNull { w -> w.sumOf { it.bpm }.toDouble() / w.size }
            ?: return null
        return RestingHeartRate(
            start = night.end,
            zone = night.zone,
            bpm = Math.round(lowest).toInt(),
            source = night.source,
            version = maxOf(night.version, readings.maxOf { it.version }),
            readings = readings.size,
        )
    }

    private fun overlapSeconds(hour: HourlyVital, night: SleepSession): Long =
        (minOf(hour.end, night.end).epochSecond - maxOf(hour.start, night.start).epochSecond).coerceAtLeast(0)
}
