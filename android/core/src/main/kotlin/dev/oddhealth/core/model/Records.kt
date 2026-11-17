package dev.oddhealth.core.model

import java.time.Instant
import java.time.ZoneOffset

/**
 * Health data as the app stores it, independent of where it came from (ring,
 * Even export) and where it goes (Health Connect, other services later).
 *
 * Every record has a stable [key] and a [version]. The same hour or bucket is
 * often seen more than once while it is still filling up; a higher version
 * replaces a lower one, both locally and in sync targets.
 */
sealed interface HealthRecord {
    val kind: RecordKind
    val start: Instant
    val end: Instant
    val zone: ZoneOffset
    val source: DataSource

    /** When we saw this value; later observations win. Milliseconds since epoch. */
    val version: Long

    val key: String get() = "${kind.id}:${start.epochSecond}"
}

enum class DataSource { RING, EVEN_EXPORT }

enum class RecordKind(val id: String) {
    HEART_RATE_HOUR("hr"),
    HEART_RATE_READING("hr_live"),
    HRV_HOUR("hrv"),
    SPO2_HOUR("spo2"),
    ACTIVITY_BUCKET("act"),
    SLEEP_SESSION("sleep"),
    SKIN_TEMPERATURE("skin"),
    RESTING_HEART_RATE("rhr"),
    ;

    companion object {
        fun fromId(id: String): RecordKind = entries.first { it.id == id }
    }
}

/** One hour of heart rate (bpm), HRV (ms) or SpO2 (%). */
data class HourlyVital(
    override val kind: RecordKind,
    override val start: Instant,
    override val zone: ZoneOffset,
    val min: Int,
    val avg: Int,
    val max: Int,
    override val source: DataSource,
    override val version: Long,
) : HealthRecord {
    init {
        require(kind == RecordKind.HEART_RATE_HOUR || kind == RecordKind.HRV_HOUR || kind == RecordKind.SPO2_HOUR)
    }

    override val end: Instant get() = start.plusSeconds(3600)
}

/** A single live heart-rate value the ring reported at a known time. */
data class HeartRateReading(
    override val start: Instant,
    override val zone: ZoneOffset,
    val bpm: Int,
    override val source: DataSource,
    override val version: Long,
) : HealthRecord {
    override val kind get() = RecordKind.HEART_RATE_READING
    override val end: Instant get() = start
}

/** Ten minutes of steps and calories. */
data class ActivityBucket(
    override val start: Instant,
    override val zone: ZoneOffset,
    val steps: Int,
    val activeKcal: Int,
    val totalKcal: Int,
    override val source: DataSource,
    override val version: Long,
) : HealthRecord {
    override val kind get() = RecordKind.ACTIVITY_BUCKET
    override val end: Instant get() = start.plusSeconds(600)
    val restingKcal: Int get() = totalKcal - activeKcal
}

enum class SleepStageType { AWAKE, REM, LIGHT, DEEP }

data class SleepStage(val type: SleepStageType, val start: Instant, val end: Instant)

data class SleepSession(
    override val start: Instant,
    override val end: Instant,
    override val zone: ZoneOffset,
    val stages: List<SleepStage>,
    /** The ring's own score, 0–100. Not supported by Health Connect. */
    val score: Int?,
    /** Percent of time in bed spent asleep. Not supported by Health Connect. */
    val efficiency: Int?,
    override val source: DataSource,
    override val version: Long,
) : HealthRecord {
    override val kind get() = RecordKind.SLEEP_SESSION

    fun total(type: SleepStageType): Long =
        stages.filter { it.type == type }.sumOf { it.end.epochSecond - it.start.epochSecond }

    val asleepSeconds: Long get() = total(SleepStageType.REM) + total(SleepStageType.LIGHT) + total(SleepStageType.DEEP)
}

/** Nightly skin temperature measured during [start]..[end] (the sleep session). */
data class SkinTemperature(
    override val start: Instant,
    override val end: Instant,
    override val zone: ZoneOffset,
    val celsius: Double,
    override val source: DataSource,
    override val version: Long,
) : HealthRecord {
    override val kind get() = RecordKind.SKIN_TEMPERATURE
}
