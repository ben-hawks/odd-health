package dev.oddhealth.app.ui.model

import dev.oddhealth.core.model.ActivityBucket
import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.HeartRateReading
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.RestingHeartRate
import dev.oddhealth.core.model.SkinBaseline
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.model.SleepStageType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Records for one day plus what is needed to describe them. */
class DayData(
    val date: LocalDate,
    val zone: ZoneId,
    /** Records starting from the evening before [date] (for last night's sleep) to the end of [date]. */
    val records: List<HealthRecord>,
    /** Synced versions by record key. */
    val synced: Map<String, Long>,
    /** Kinds Health Connect accepts right now. */
    val writable: Set<RecordKind>,
    val skinHistory: List<SkinTemperature>,
) {
    val dayStart: Instant = date.atStartOfDay(zone).toInstant()
    val dayEnd: Instant = date.plusDays(1).atStartOfDay(zone).toInstant()

    private fun inDay(r: HealthRecord) = r.start >= dayStart && r.start < dayEnd

    fun hours(kind: RecordKind) = records.filter { it.kind == kind && inDay(it) }.filterIsInstance<HourlyVital>()
    val readings get() = records.filter { inDay(it) }.filterIsInstance<HeartRateReading>()
    val buckets get() = records.filter { inDay(it) }.filterIsInstance<ActivityBucket>()

    /** The main sleep that ended on this day: the longest, so a nap doesn't replace the night. */
    val night: SleepSession? get() =
        records.filterIsInstance<SleepSession>().filter { it.end >= dayStart && it.end < dayEnd }
            .maxByOrNull { it.end.epochSecond - it.start.epochSecond }

    val resting: RestingHeartRate? get() =
        records.filterIsInstance<RestingHeartRate>().filter { it.start >= dayStart && it.start < dayEnd }.maxByOrNull { it.start }

    val skin: SkinTemperature? get() = night?.let { n -> records.filterIsInstance<SkinTemperature>().firstOrNull { it.start == n.start } }

    fun mark(r: HealthRecord): SyncMark =
        if ((synced[r.key] ?: Long.MIN_VALUE) >= r.version) SyncMark.SYNCED else SyncMark.PENDING

    fun hourOf(i: Instant) = i.atZone(zone).hour
}

/** Texts the mapping needs; supplied from string resources by the view model. */
interface Words {
    fun noData(): String
    fun liveReading(): String
    fun fromRing(): String
    fun hrCaption(avg: Int, latest: Int?, at: String?): String
    fun rangeCaption(min: Int, max: Int, unit: String): String
    fun activityCaption(kcal: Int, active: Int): String
    fun sleepCaption(from: String, to: String, score: Int?, efficiency: Int?): String
    fun skinCaption(delta: Double?): String
    fun hrNote(): String
    fun sleepNote(): String
    fun skinNote(): String
    fun stage(type: SleepStageType): String
    fun score(): String
    fun efficiency(): String
    fun steps(n: Int): String
    fun stepsUnit(): String
    fun restingLabel(): String
    /** [readings]: how many readings the estimate came from; 0 = hourly averages. */
    fun restingDetail(readings: Int): String
    fun kcal(n: Int, active: Int): String
}

object UiMapping {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    fun vital(day: DayData, kind: RecordKind): VitalSummary? {
        val hours = day.hours(kind).sortedBy { it.start }
        if (hours.isEmpty()) return null
        val latestReading = if (kind == RecordKind.HEART_RATE_HOUR) day.readings.maxByOrNull { it.start } else null
        return VitalSummary(
            latest = latestReading?.bpm ?: hours.last().avg,
            latestAt = (latestReading?.start ?: hours.last().start).atZone(day.zone).toLocalTime(),
            min = hours.minOf { it.min },
            avg = hours.map { it.avg }.average().roundToInt(),
            max = hours.maxOf { it.max },
            hours = hours.map { HourRange(day.hourOf(it.start), it.min, it.avg, it.max) },
            resting = if (kind == RecordKind.HEART_RATE_HOUR) day.resting?.bpm else null,
        )
    }

    fun activity(day: DayData): ActivitySummary? {
        val b = day.buckets
        if (b.isEmpty()) return null
        val byHour = IntArray(24)
        b.forEach { byHour[day.hourOf(it.start)] += it.steps }
        return ActivitySummary(b.sumOf { it.steps }, b.sumOf { it.totalKcal }, b.sumOf { it.activeKcal }, byHour.toList())
    }

    fun sleep(day: DayData): SleepSummary? {
        val n = day.night ?: return null
        val span = (n.end.epochSecond - n.start.epochSecond).toFloat().coerceAtLeast(1f)
        return SleepSummary(
            bedtime = n.start.atZone(day.zone).toLocalTime().withSecond(0).withNano(0),
            wake = n.end.atZone(day.zone).toLocalTime().withSecond(0).withNano(0),
            asleep = Duration.ofSeconds(n.asleepSeconds),
            score = n.score,
            efficiency = n.efficiency,
            stages = n.stages.map {
                StageSpan(stageKind(it.type), (it.start.epochSecond - n.start.epochSecond) / span, (it.end.epochSecond - n.start.epochSecond) / span)
            },
            totals = SleepStageType.entries.associate { stageKind(it) to Duration.ofSeconds(n.total(it)) },
        )
    }

    fun skin(day: DayData): SkinSummary? {
        val s = day.skin ?: return null
        return SkinSummary(s.celsius, SkinBaseline.forNight(s, day.skinHistory)?.let { s.celsius - it })
    }

    fun stageKind(t: SleepStageType) = when (t) {
        SleepStageType.AWAKE -> StageKind.AWAKE
        SleepStageType.REM -> StageKind.REM
        SleepStageType.LIGHT -> StageKind.LIGHT
        SleepStageType.DEEP -> StageKind.DEEP
    }

    fun today(day: DayData, ring: RingStatus, sync: SyncStatus) = TodayState(
        date = day.date, ring = ring, sync = sync,
        sleep = sleep(day),
        heartRate = vital(day, RecordKind.HEART_RATE_HOUR),
        hrv = vital(day, RecordKind.HRV_HOUR),
        spo2 = vital(day, RecordKind.SPO2_HOUR),
        activity = activity(day),
        skin = skin(day),
    )

    fun detail(metric: Metric, day: DayData, w: Words): MetricDetailState {
        val empty = MetricDetailState(metric, day.date, "—", "", w.noData(), rows = emptyList(), appOnlyNote = null)
        return when (metric) {
            Metric.HEART_RATE, Metric.HRV, Metric.SPO2 -> {
                val kind = when (metric) {
                    Metric.HEART_RATE -> RecordKind.HEART_RATE_HOUR
                    Metric.HRV -> RecordKind.HRV_HOUR
                    else -> RecordKind.SPO2_HOUR
                }
                val unit = when (metric) {
                    Metric.HEART_RATE -> "bpm"
                    Metric.HRV -> "ms"
                    else -> "%"
                }
                val v = vital(day, kind) ?: return empty
                val hourRows = day.hours(kind).map {
                    it.start to DetailRow("%02d:00".format(day.hourOf(it.start)), "${it.avg} $unit", "${it.min}–${it.max}", day.mark(it))
                }
                val liveRows = if (metric == Metric.HEART_RATE) {
                    day.readings.map { it.start to DetailRow(it.start.atZone(day.zone).format(hm), "${it.bpm} $unit", w.liveReading(), day.mark(it)) }
                } else {
                    emptyList()
                }
                MetricDetailState(
                    metric, day.date,
                    headline = if (metric == Metric.HEART_RATE) "${v.min}–${v.max}" else v.avg.toString(),
                    headlineUnit = unit,
                    caption = if (metric == Metric.HEART_RATE) w.hrCaption(v.avg, v.latest, v.latestAt?.format(hm)) else w.rangeCaption(v.min, v.max, unit),
                    vital = v,
                    rows = listOfNotNull(
                        day.resting?.takeIf { metric == Metric.HEART_RATE }?.let {
                            DetailRow(w.restingLabel(), "${it.bpm} $unit", w.restingDetail(it.readings), day.mark(it))
                        },
                    ) + (hourRows + liveRows).sortedByDescending { it.first }.map { it.second },
                    appOnlyNote = if (metric == Metric.HEART_RATE) w.hrNote() else null,
                )
            }
            Metric.ACTIVITY -> {
                val a = activity(day) ?: return empty
                val rows = day.buckets.groupBy { day.hourOf(it.start) }.toSortedMap(reverseOrder()).map { (hour, list) ->
                    DetailRow(
                        "%02d:00".format(hour),
                        w.steps(list.sumOf { it.steps }),
                        w.kcal(list.sumOf { it.totalKcal }, list.sumOf { it.activeKcal }),
                        if (list.all { day.mark(it) == SyncMark.SYNCED }) SyncMark.SYNCED else SyncMark.PENDING,
                    )
                }
                MetricDetailState(metric, day.date, "%,d".format(a.steps), w.stepsUnit(),
                    w.activityCaption(a.totalKcal, a.activeKcal), activity = a, rows = rows, appOnlyNote = null)
            }
            Metric.SLEEP -> {
                val n = day.night ?: return empty
                val s = sleep(day)!!
                val mark = day.mark(n)
                val asleep = n.asleepSeconds.coerceAtLeast(1)
                val rows = listOf(SleepStageType.DEEP, SleepStageType.LIGHT, SleepStageType.REM, SleepStageType.AWAKE).map { t ->
                    val secs = n.total(t)
                    DetailRow(w.stage(t), formatHm(secs), if (t == SleepStageType.AWAKE) null else "${(secs * 100 / asleep)}%", mark)
                } + listOfNotNull(
                    n.score?.let { DetailRow(w.score(), it.toString(), w.fromRing(), SyncMark.APP_ONLY) },
                    n.efficiency?.let { DetailRow(w.efficiency(), "$it%", w.fromRing(), SyncMark.APP_ONLY) },
                )
                MetricDetailState(
                    metric, day.date, formatHm(n.asleepSeconds), "",
                    w.sleepCaption(s.bedtime.format(hm), s.wake.format(hm), n.score, n.efficiency),
                    sleep = s, rows = rows, appOnlyNote = if (n.score != null || n.efficiency != null) w.sleepNote() else null,
                )
            }
            Metric.SKIN_TEMPERATURE -> {
                val t = day.skin ?: return empty
                val sk = skin(day)!!
                MetricDetailState(
                    metric, day.date, "%.1f".format(t.celsius), "°C", w.skinCaption(sk.deltaFromBaseline),
                    rows = listOf(DetailRow(w.fromRing(), "%.1f °C".format(t.celsius),
                        sk.deltaFromBaseline?.let { "%+.1f °C".format(it) }, day.mark(t))),
                    appOnlyNote = w.skinNote(),
                )
            }
        }
    }

    fun formatHm(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
    }
}
