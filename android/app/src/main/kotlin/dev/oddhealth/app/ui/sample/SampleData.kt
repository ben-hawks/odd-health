package dev.oddhealth.app.ui.sample

import dev.oddhealth.app.ui.model.ActivitySummary
import dev.oddhealth.app.ui.model.DetailRow
import dev.oddhealth.app.ui.model.HealthConnectState
import dev.oddhealth.app.ui.model.HourRange
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.model.MetricDetailState
import dev.oddhealth.app.ui.model.RingEvent
import dev.oddhealth.app.ui.model.RingScreenState
import dev.oddhealth.app.ui.model.RingStatus
import dev.oddhealth.app.ui.model.SkinSummary
import dev.oddhealth.app.ui.model.SleepSummary
import dev.oddhealth.app.ui.model.StageKind
import dev.oddhealth.app.ui.model.StageSpan
import dev.oddhealth.app.ui.model.SyncMark
import dev.oddhealth.app.ui.model.SyncScreenState
import dev.oddhealth.app.ui.model.SyncStatus
import dev.oddhealth.app.ui.model.SyncTypeRow
import dev.oddhealth.app.ui.model.TodayState
import dev.oddhealth.app.ui.model.TypeStatus
import dev.oddhealth.app.ui.model.VitalSummary
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Made-up but plausible data for previews and screenshot tests. */
object SampleData {
    val date: LocalDate = LocalDate.of(2026, 11, 17)
    private val now: Instant = Instant.parse("2026-11-17T17:42:00Z")

    val ring = RingStatus("EVEN R1 8CA05D", connected = true, batteryPercent = 34, charging = false, worn = true,
        firmware = "2.3.2.0008", lastData = now.minusSeconds(180))

    val sync = SyncStatus(HealthConnectState.READY, lastSync = now.minusSeconds(240), pending = 0)

    private fun wave(hour: Int, base: Double, amp: Double, phase: Double = 0.0) =
        base + amp * sin((hour - 4 + phase) / 24.0 * 2 * PI)

    private fun vital(base: Double, amp: Double, spread: Int, hours: Int, latest: Int?): VitalSummary {
        val list = (0 until hours).map { h ->
            val avg = wave(h, base, amp).roundToInt() + (h * 7 % 5) - 2
            HourRange(h, avg - spread - (h % 3), avg, avg + spread + (h * 5 % 7))
        }
        return VitalSummary(latest, LocalTime.of(11, 39), list.minOf { it.min }, list.map { it.avg }.average().roundToInt(),
            list.maxOf { it.max }, list)
    }

    val heartRate = vital(base = 78.0, amp = 14.0, spread = 8, hours = 12, latest = 84).copy(resting = 58)
    val hrv = vital(base = 48.0, amp = -14.0, spread = 4, hours = 12, latest = 44)
    val spo2 = VitalSummary(96, LocalTime.of(11, 39), 91, 96, 99,
        (0 until 12).map { HourRange(it, 94 + it % 3, 96 + (it % 2), 98 + (it % 2)) })

    val activity = ActivitySummary(
        steps = 4_410, totalKcal = 1_312, activeKcal = 186,
        stepsByHour = listOf(0, 0, 0, 0, 0, 0, 0, 120, 640, 410, 1_180, 560, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            .let { it.take(12) + List(12) { 0 } },
    )

    val sleep: SleepSummary = run {
        // Matches the totals of a real night: awake 16.5 min, light 2h07, deep 1h26, REM 35 min.
        val runs = listOf(
            StageKind.AWAKE to 25, StageKind.LIGHT to 28, StageKind.DEEP to 48, StageKind.LIGHT to 12, StageKind.DEEP to 64,
            StageKind.LIGHT to 30, StageKind.REM to 20, StageKind.AWAKE to 4, StageKind.LIGHT to 46, StageKind.DEEP to 60,
            StageKind.LIGHT to 40, StageKind.REM to 30, StageKind.LIGHT to 52, StageKind.AWAKE to 4, StageKind.REM to 20,
            StageKind.LIGHT to 46,
        )
        val total = runs.sumOf { it.second }.toFloat()
        var t = 0
        val spans = runs.map { (k, n) -> StageSpan(k, t / total, (t + n) / total).also { t += n } }
        val totals = StageKind.entries.associateWith { k -> Duration.ofSeconds(runs.filter { it.first == k }.sumOf { it.second } * 30L) }
        SleepSummary(LocalTime.of(1, 2), LocalTime.of(5, 27), Duration.ofMinutes(248), score = 53, efficiency = 93,
            stages = spans, totals = totals)
    }

    val skin = SkinSummary(35.6, +0.3)

    val today = TodayState(date, ring, sync, sleep, heartRate, hrv, spo2, activity, skin)

    val heartRateDetail = MetricDetailState(
        metric = Metric.HEART_RATE, date = date,
        headline = "${heartRate.min}–${heartRate.max}", headlineUnit = "bpm",
        caption = "Average ${heartRate.avg} bpm · latest ${heartRate.latest} bpm at 11:39",
        vital = heartRate,
        rows = heartRate.hours.reversed().map {
            DetailRow(
                time = "%02d:00".format(it.hour),
                value = "${it.avg} bpm",
                detail = "${it.min}–${it.max}",
                mark = if (it.hour == heartRate.hours.last().hour) SyncMark.PENDING else SyncMark.SYNCED,
            )
        },
        appOnlyNote = "Hourly minimum and maximum are shown here only. Health Connect gets the hourly average and each live reading.",
    )

    val sleepDetail = MetricDetailState(
        metric = Metric.SLEEP, date = date, headline = "4h 08m", headlineUnit = "asleep",
        caption = "01:02 – 05:27 · score 53 · efficiency 93%", sleep = sleep,
        rows = listOf(
            DetailRow("Deep", "1h 26m", "35%", SyncMark.SYNCED),
            DetailRow("Light", "2h 07m", "51%", SyncMark.SYNCED),
            DetailRow("REM", "35m", "14%", SyncMark.SYNCED),
            DetailRow("Awake", "17m", null, SyncMark.SYNCED),
            DetailRow("Sleep score", "53", "From the ring", SyncMark.APP_ONLY),
            DetailRow("Efficiency", "93%", "From the ring", SyncMark.APP_ONLY),
        ),
        appOnlyNote = "Health Connect has no field for the sleep score or efficiency; they are added to the session's notes.",
    )

    val ringScreen = RingScreenState(
        ring = ring, listening = true, batteryUnrestricted = true,
        events = listOf(
            RingEvent(LocalTime.of(11, 39), "Heart rate · hours 10–11 · now 84 bpm"),
            RingEvent(LocalTime.of(11, 39), "Activity · 2 buckets · 47 kcal"),
            RingEvent(LocalTime.of(11, 39), "SpO₂ · hours 10–11 · now 96%"),
            RingEvent(LocalTime.of(11, 7), "Even app synced"),
            RingEvent(LocalTime.of(10, 51), "Battery 34% · not charging"),
            RingEvent(LocalTime.of(5, 35), "Sleep · 01:02–05:27 · score 53"),
        ),
    )

    val syncScreen = SyncScreenState(
        sync = sync,
        types = listOf(
            SyncTypeRow("Heart rate", "Heart rate", TypeStatus.WRITTEN, "Hourly average and live readings"),
            SyncTypeRow("Resting heart rate", "Resting heart rate", TypeStatus.WRITTEN, "Estimated: lowest hourly average during sleep"),
            SyncTypeRow("Heart rate range", null, TypeStatus.APP_ONLY, "Hourly minimum and maximum"),
            SyncTypeRow("HRV", "Heart rate variability", TypeStatus.WRITTEN, "Hourly average (RMSSD)"),
            SyncTypeRow("Blood oxygen", "Oxygen saturation", TypeStatus.WRITTEN, "Hourly average"),
            SyncTypeRow("Steps", "Steps", TypeStatus.WRITTEN, "10-minute totals"),
            SyncTypeRow("Calories", "Total and active calories", TypeStatus.WRITTEN, "10-minute totals"),
            SyncTypeRow("Sleep", "Sleep session with stages", TypeStatus.WRITTEN, null),
            SyncTypeRow("Sleep score, efficiency", null, TypeStatus.APP_ONLY, "Copied into the sleep session's notes"),
            SyncTypeRow("Nightly temperature", "Body temperature (finger)", TypeStatus.WRITTEN, "Measured at the finger, so about 1 °C below core temperature"),
            SyncTypeRow("Skin temperature", "Skin temperature", TypeStatus.WRITTEN, "Change from your 30-night baseline"),
        ),
        stored = mapOf(Metric.HEART_RATE to 2_591, Metric.HRV to 2_445, Metric.SPO2 to 2_237, Metric.ACTIVITY to 9_064,
            Metric.SLEEP to 208, Metric.SKIN_TEMPERATURE to 130),
        oldest = LocalDate.of(2026, 5, 4),
    )
}
