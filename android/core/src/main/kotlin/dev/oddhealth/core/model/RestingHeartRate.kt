package dev.oddhealth.core.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Resting heart rate for one night. The ring does not report one, so this is
 * our estimate: the lowest hourly average heart rate during the main sleep.
 * [start] is the time the sleep ended.
 */
data class RestingHeartRate(
    override val start: Instant,
    override val zone: ZoneOffset,
    val bpm: Int,
    override val source: DataSource,
    override val version: Long,
) : HealthRecord {
    override val kind get() = RecordKind.RESTING_HEART_RATE
    override val end: Instant get() = start
}

object RestingHeartRateEstimate {
    /** Shorter sleeps (naps) don't give a resting heart rate. */
    val MIN_SLEEP: Duration = Duration.ofHours(3)

    /** An hour counts if at least half of it falls inside the sleep. */
    fun forNight(night: SleepSession, heartRate: List<HourlyVital>): RestingHeartRate? {
        if (Duration.between(night.start, night.end) < MIN_SLEEP) return null
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

    private fun overlapSeconds(hour: HourlyVital, night: SleepSession): Long =
        (minOf(hour.end, night.end).epochSecond - maxOf(hour.start, night.start).epochSecond).coerceAtLeast(0)
}
