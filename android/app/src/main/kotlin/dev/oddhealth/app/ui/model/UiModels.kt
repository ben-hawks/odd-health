package dev.oddhealth.app.ui.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Everything the screens show. Built from stored records by the view models. */

enum class Metric { SLEEP, HEART_RATE, HRV, SPO2, ACTIVITY, SKIN_TEMPERATURE }

/** Whether a value has reached Health Connect. */
enum class SyncMark { SYNCED, PENDING, APP_ONLY }

data class RingStatus(
    val name: String,
    val connected: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    val worn: Boolean?,
    val firmware: String?,
    val lastData: Instant?,
)

enum class HealthConnectState { READY, NEEDS_PERMISSION, NOT_INSTALLED, UNSUPPORTED }

data class SyncStatus(
    val state: HealthConnectState,
    val lastSync: Instant?,
    val pending: Int,
)

/** One hour of a vital sign; null values mean no data for that hour. */
data class HourRange(val hour: Int, val min: Int, val avg: Int, val max: Int)

data class VitalSummary(
    val latest: Int?,
    val latestAt: LocalTime?,
    val min: Int,
    val avg: Int,
    val max: Int,
    val hours: List<HourRange>,
    /** Estimated resting heart rate from last night (heart rate only). */
    val resting: Int? = null,
)

data class ActivitySummary(
    val steps: Int,
    val totalKcal: Int,
    val activeKcal: Int,
    /** Steps per hour, 24 entries. */
    val stepsByHour: List<Int>,
)

enum class StageKind { AWAKE, REM, LIGHT, DEEP }

/** A run of one sleep stage, as fractions (0..1) of the night. */
data class StageSpan(val kind: StageKind, val from: Float, val to: Float)

data class SleepSummary(
    val bedtime: LocalTime,
    val wake: LocalTime,
    val asleep: Duration,
    val score: Int?,
    val efficiency: Int?,
    val stages: List<StageSpan>,
    val totals: Map<StageKind, Duration>,
)

data class SkinSummary(val celsius: Double, val deltaFromBaseline: Double?)

data class TodayState(
    val date: LocalDate,
    val ring: RingStatus,
    val sync: SyncStatus,
    val sleep: SleepSummary?,
    val heartRate: VitalSummary?,
    val hrv: VitalSummary?,
    val spo2: VitalSummary?,
    val activity: ActivitySummary?,
    val skin: SkinSummary?,
)

/** A row in a metric's detail list. */
data class DetailRow(val time: String, val value: String, val detail: String?, val mark: SyncMark)

data class MetricDetailState(
    val metric: Metric,
    val date: LocalDate,
    val headline: String,
    val headlineUnit: String,
    val caption: String,
    val vital: VitalSummary? = null,
    val activity: ActivitySummary? = null,
    val sleep: SleepSummary? = null,
    val rows: List<DetailRow>,
    val appOnlyNote: String?,
)

data class RingEvent(val time: LocalTime, val text: String)

data class RingScreenState(
    val ring: RingStatus,
    val listening: Boolean,
    val events: List<RingEvent>,
    val debugLogging: Boolean = false,
    val frequentUpdates: Boolean = false,
    /** Exempt from battery optimisation; null while unknown. */
    val batteryUnrestricted: Boolean? = null,
)

data class DebugState(val enabled: Boolean, val files: Int, val bytes: Long)

enum class TypeStatus { WRITTEN, APP_ONLY, NEEDS_PERMISSION }

/** One line of "what goes to Health Connect". */
data class SyncTypeRow(val title: String, val healthConnectType: String?, val status: TypeStatus, val note: String?)

data class SyncScreenState(
    val sync: SyncStatus,
    val types: List<SyncTypeRow>,
    val stored: Map<Metric, Int>,
    val oldest: LocalDate?,
    val syncing: Boolean = false,
)
