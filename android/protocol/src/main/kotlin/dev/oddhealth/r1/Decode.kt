package dev.oddhealth.r1

/** Decoded payloads of ring -> phone frames. Times are Unix seconds. */
sealed interface Decoded

data class HourRecord(val hour: Int, val avg: Int, val max: Int, val min: Int, val ts: Long?)

/** Hourly min/max/avg for heart rate (bpm), SpO2 (%) or HRV (ms). */
data class Vitals(
    val cmd: Int,
    val tzOffsetMin: Int,
    val dayBase: Long?,
    val current: Int,
    val currentTs: Long?,
    val records: List<HourRecord>,
) : Decoded

data class ActivityRecord(
    val slot: Int,
    val steps: Int,
    val kcalActive: Int,
    val kcalTotal: Int,
    val ts: Long?,
)

/** 10-minute buckets of steps and calories. */
data class Activity(val tzOffsetMin: Int, val dayBase: Long?, val records: List<ActivityRecord>) : Decoded

/** type: 0 awake, 1 REM, 2 light, 3 deep. */
data class SleepStage(val type: Int, val halfMinutes: Int)

data class Sleep(
    val startTs: Long,
    val endTs: Long,
    val tzOffsetMin: Int,
    val efficiency: Int,
    val score: Int,
    val skinTempC: Double?,
    val asleepSec: Int,
    val awakeSec: Int,
    val remSec: Int,
    val lightSec: Int,
    val deepSec: Int,
    val stages: List<SleepStage>,
) : Decoded

/** Interval-only sleep record; endpoints are relative seconds with an unknown base. */
data class SleepInterval(val relStart: Long, val relEnd: Long) : Decoded

data class DeviceStatus(val batteryPercent: Int, val chargeState: Int?) : Decoded

data class DeviceInfo(val firmware: String, val hardware: String?) : Decoded

private fun dayAnchored(tzMin: Int, dayBase: Long): Boolean =
    tzMin in -840..840 && dayBase != 0L && Math.floorMod(dayBase + tzMin * 60L, 86400L) == 0L

private fun decodeVitals(cmd: Int, d: ByteArray): Vitals {
    val wide = cmd == R1.HEALTH_HRV
    require(d.size >= 12) { "vitals payload too short" }
    val count = d.u8(0)
    val tzMin = d.i16(1)
    val dayBase = d.u32(3)
    val currentTs = d.u32(7)
    val off = if (wide) 13 else 12
    val stride = if (wide) 7 else 4
    require(off + count * stride <= d.size) { "vitals payload truncated" }
    val anchored = dayAnchored(tzMin, dayBase)
    val records = List(count) { i ->
        val o = off + i * stride
        val hour = d.u8(o)
        val ts = if (anchored && hour < 24) dayBase + hour * 3600L else null
        if (wide) HourRecord(hour, d.u16(o + 1), d.u16(o + 3), d.u16(o + 5), ts)
        else HourRecord(hour, d.u8(o + 1), d.u8(o + 2), d.u8(o + 3), ts)
    }
    return Vitals(
        cmd = cmd,
        tzOffsetMin = tzMin,
        dayBase = dayBase.takeIf { anchored },
        current = if (wide) d.u16(11) else d.u8(11),
        currentTs = currentTs.takeIf { anchored && it >= dayBase && it < dayBase + 86400 },
        records = records,
    )
}

private fun decodeActivity(d: ByteArray): Activity {
    require(d.size >= 7) { "activity payload too short" }
    val count = d.u8(0)
    val tzMin = d.i16(1)
    val dayBase = d.u32(3)
    require(7 + count * 7 <= d.size) { "activity payload truncated" }
    val anchored = dayAnchored(tzMin, dayBase)
    val records = List(count) { i ->
        val o = 7 + i * 7
        val slot = d.u8(o)
        ActivityRecord(
            slot = slot,
            steps = d.u16(o + 1),
            kcalActive = d.u16(o + 3),
            kcalTotal = d.u16(o + 5),
            ts = if (anchored && slot < 144) dayBase + slot * 600L else null,
        )
    }
    return Activity(tzMin, dayBase.takeIf { anchored }, records)
}

private fun decodeSleep(d: ByteArray): Decoded {
    require(d.isNotEmpty()) { "empty sleep payload" }
    if (d.u8(0) == 2) {
        require(d.size >= 20) { "sleep type-2 payload too short" }
        return SleepInterval(d.u32(12), d.u32(16))
    }
    require(d.u8(0) == 1 && d.size >= 32) { "unknown sleep record type ${d.u8(0)}" }
    val runs = d.u8(30)
    require(32 + runs * 3 <= d.size) { "sleep payload truncated" }
    val temp = d.u16(8)
    return Sleep(
        startTs = d.u32(12),
        endTs = d.u32(16),
        tzOffsetMin = d.i16(10),
        efficiency = d.u8(1),
        score = d.u8(2),
        skinTempC = if (temp != 0) temp / 10.0 else null,
        asleepSec = d.u16(20),
        awakeSec = d.u16(22),
        remSec = d.u16(24),
        lightSec = d.u16(26),
        deepSec = d.u16(28),
        stages = List(runs) { i -> SleepStage(d.u8(32 + i * 3), d.u16(33 + i * 3)) },
    )
}

private fun ascii(d: ByteArray, from: Int, to: Int): String {
    val end = (from until to).firstOrNull { d[it].toInt() == 0 } ?: to
    return String(d, from, end - from, Charsets.US_ASCII)
}

/** Decode a ring -> phone frame's payload; null if not understood. Throws on malformed data. */
fun decode(frame: Frame): Decoded? {
    val d = frame.data
    if (d.isEmpty() || !frame.fromRing) return null
    if (frame.module == R1.MODULE_HEALTH && frame.subCmd == R1.HEALTH_SUB_DAILY) {
        return when (frame.cmd) {
            R1.HEALTH_HEART_RATE, R1.HEALTH_SPO2, R1.HEALTH_TEMPERATURE, R1.HEALTH_HRV -> decodeVitals(frame.cmd, d)
            R1.HEALTH_ACTIVITY -> decodeActivity(d)
            R1.HEALTH_SLEEP -> decodeSleep(d)
            else -> null
        }
    }
    if (frame.module == R1.MODULE_SYSTEM && frame.cmd == 0) {
        return when (frame.subCmd) {
            R1.SYS_DEVICE_STATUS -> DeviceStatus(d.u8(0), if (d.size > 1) d.u8(1) else null)
            R1.SYS_DEVICE_INFO -> DeviceInfo(
                ascii(d, 0, minOf(16, d.size)),
                if (d.size >= 32) ascii(d, 16, 32) else null,
            )
            else -> null
        }
    }
    return null
}
