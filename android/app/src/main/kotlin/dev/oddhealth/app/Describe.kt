package dev.oddhealth.app

import dev.oddhealth.r1.Activity
import dev.oddhealth.r1.Decoded
import dev.oddhealth.r1.DeviceInfo
import dev.oddhealth.r1.DeviceStatus
import dev.oddhealth.r1.R1
import dev.oddhealth.r1.Sleep
import dev.oddhealth.r1.SleepInterval
import dev.oddhealth.r1.Vitals
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private fun local(ts: Long, tzOffsetMin: Int) =
    Instant.ofEpochSecond(ts).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetMin * 60))

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private fun day(ts: Long?, tzOffsetMin: Int): String =
    ts?.let { local(it, tzOffsetMin).toLocalDate().toString() } ?: "undated"

private fun duration(sec: Int): String = "%dh%02d".format(sec / 3600, sec % 3600 / 60)

/** One line describing a decoded payload. */
fun describe(decoded: Decoded): String = when (decoded) {
    is Vitals -> {
        val unit = when (decoded.cmd) {
            R1.HEALTH_HEART_RATE -> "bpm"
            R1.HEALTH_HRV -> "ms"
            R1.HEALTH_SPO2 -> "%"
            else -> ""
        }
        val hours = decoded.records
        val range = if (hours.isEmpty()) "no hours" else "hours ${hours.first().hour}–${hours.last().hour}"
        "${R1.HEALTH_CMDS[decoded.cmd]} ${day(decoded.dayBase, decoded.tzOffsetMin)}: $range, now ${decoded.current} $unit"
    }
    is Activity ->
        "activity ${day(decoded.dayBase, decoded.tzOffsetMin)}: ${decoded.records.size} buckets, " +
            "${decoded.records.sumOf { it.steps }} steps, ${decoded.records.sumOf { it.kcalTotal }} kcal"
    is Sleep ->
        "sleep ${local(decoded.startTs, decoded.tzOffsetMin).format(HM)}–${local(decoded.endTs, decoded.tzOffsetMin).format(HM)}: " +
            "${duration(decoded.asleepSec)} asleep, score ${decoded.score}" +
            (decoded.skinTempC?.let { ", skin %.1f °C".format(it) } ?: "")
    is SleepInterval -> "sleep interval ${duration((decoded.relEnd - decoded.relStart).toInt())} (no date)"
    is DeviceStatus -> {
        val state = when (decoded.chargeState) {
            1 -> ", charging"
            2 -> ", not charging"
            3 -> ", full"
            else -> ""
        }
        "battery ${decoded.batteryPercent}%$state"
    }
    is DeviceInfo -> "firmware ${decoded.firmware}" + (decoded.hardware?.let { ", hardware $it" } ?: "")
}
