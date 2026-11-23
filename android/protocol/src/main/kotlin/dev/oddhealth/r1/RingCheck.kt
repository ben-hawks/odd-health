package dev.oddhealth.r1

/**
 * Read-only checks of what the ring sends, to run after a firmware update.
 *
 * A check run sends the same requests as a normal sync (plus a temperature
 * request, which the ring has so far ignored), records every notification for
 * a few seconds, and [RingCheck.evaluate] compares what came back with what
 * this app expects from the firmware it was built against. Each result names
 * whether the ring behaved as before, so a firmware change shows up as a
 * warning rather than as silently wrong data.
 */
enum class CheckOutcome { PASS, WARN, FAIL, INFO }

/** [id] is stable across runs, so results can be compared between firmware versions. */
data class CheckItem(val id: String, val title: String, val outcome: CheckOutcome, val detail: String)

data class CheckReport(
    /** Unix milliseconds. */
    val startedAt: Long,
    val firmware: String?,
    val hardware: String?,
    val items: List<CheckItem>,
) {
    fun count(outcome: CheckOutcome) = items.count { it.outcome == outcome }

    /** Plain-text lines, for the debug log and for sharing. */
    fun lines(): List<String> = buildList {
        add("Ring check: firmware ${firmware ?: "unknown"}" + (hardware?.let { ", hardware $it" } ?: "") +
            " — ${count(CheckOutcome.PASS)} passed, ${count(CheckOutcome.WARN)} warnings, ${count(CheckOutcome.FAIL)} failed")
        for (item in items) add("  [${item.outcome}] ${item.title}: ${item.detail}")
    }

    /** Stored form: a header line, then one tab-separated line per item. */
    fun serialize(): String = buildString {
        append("v1\t").append(startedAt).append('\t').append(firmware.orEmpty()).append('\t').append(hardware.orEmpty()).append('\n')
        for (i in items) {
            append(i.id).append('\t').append(i.outcome.name).append('\t').append(clean(i.title)).append('\t').append(clean(i.detail)).append('\n')
        }
    }

    companion object {
        private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

        fun parse(text: String): CheckReport? {
            val lines = text.lines().filter { it.isNotEmpty() }
            val head = lines.firstOrNull()?.split('\t') ?: return null
            if (head.size < 4 || head[0] != "v1") return null
            val items = lines.drop(1).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 4) return@mapNotNull null
                val outcome = CheckOutcome.entries.firstOrNull { it.name == f[1] } ?: return@mapNotNull null
                CheckItem(f[0], f[2], outcome, f[3])
            }
            return CheckReport(head[1].toLongOrNull() ?: return null, head[2].ifEmpty { null }, head[3].ifEmpty { null }, items)
        }
    }
}

/** A frame this app wrote during the check. */
data class SentRequest(val label: String, val module: Int, val cmd: Int, val subCmd: Int, val seq: Int)

/**
 * One complete notification batch received during the check. [frame] is null
 * when the batch was not a ring frame (the Even app also transfers other data
 * on this channel) or could not be parsed; [error] says why, for the latter.
 */
class Observed(val frame: Frame?, val crcOk: Boolean, val error: String? = null)

/** A change between two reports. */
data class CheckChange(val title: String, val before: CheckOutcome?, val after: CheckOutcome?, val detail: String)

object RingCheck {
    /** Firmware versions this app's decoding was checked against. */
    val TESTED_FIRMWARE = setOf("2.3.2.0008")

    /** Ring reading intervals seen so far: 585–594 s. */
    private val READING_INTERVAL_SEC = 570..610

    /** The requests a check sends, in order, after pairAuth. */
    val REQUESTS: List<Triple<String, Int, Int>> = listOf(
        Triple("deviceInfo", R1.MODULE_SYSTEM, R1.SYS_DEVICE_INFO),
        Triple("deviceStatus", R1.MODULE_SYSTEM, R1.SYS_DEVICE_STATUS),
        Triple("heartRate", R1.MODULE_HEALTH, R1.HEALTH_HEART_RATE),
        Triple("spo2", R1.MODULE_HEALTH, R1.HEALTH_SPO2),
        Triple("hrv", R1.MODULE_HEALTH, R1.HEALTH_HRV),
        Triple("activity", R1.MODULE_HEALTH, R1.HEALTH_ACTIVITY),
        Triple("sleep", R1.MODULE_HEALTH, R1.HEALTH_SLEEP),
        Triple("temperature", R1.MODULE_HEALTH, R1.HEALTH_TEMPERATURE),
    )

    /** Build the frame for one of [REQUESTS]. */
    fun request(module: Int, code: Int, seq: Int): ByteArray =
        if (module == R1.MODULE_SYSTEM) systemRequest(code, seq) else healthRequest(code, seq)

    /**
     * @param sent the requests written, with their sequence numbers (pairAuth first).
     * @param observed every notification batch from the ring during the check.
     * @param nowSec phone clock, Unix seconds; [tzOffsetMin] the phone's UTC offset.
     * @param readingTimes stored individual heart-rate reading times (Unix seconds) of the last day, oldest first.
     * @param frequentUpdates whether the app collects individual readings at all.
     */
    fun evaluate(
        startedAt: Long,
        sent: List<SentRequest>,
        observed: List<Observed>,
        nowSec: Long,
        tzOffsetMin: Int,
        readingTimes: List<Long>,
        frequentUpdates: Boolean,
    ): CheckReport {
        val frames = observed.mapNotNull { it.frame }
        val items = mutableListOf<CheckItem>()
        fun add(id: String, title: String, outcome: CheckOutcome, detail: String) = items.add(CheckItem(id, title, outcome, detail))
        fun ackFor(req: SentRequest) = frames.firstOrNull {
            it.status == R1.STATUS_ACK && it.seq == req.seq && it.module == req.module && it.cmd == req.cmd && it.subCmd == req.subCmd
        }
        fun pushes(module: Int, cmd: Int, sub: Int) =
            frames.filter { it.status == R1.STATUS_PUSH && it.module == module && it.cmd == cmd && it.subCmd == sub }
        fun replies(module: Int, cmd: Int, sub: Int) =
            frames.filter { it.fromRing && it.module == module && it.cmd == cmd && it.subCmd == sub && it.data.isNotEmpty() }

        // --- link ------------------------------------------------------------
        val pair = frames.firstOrNull { it.module == R1.MODULE_SYSTEM && it.cmd == 0 && it.subCmd == R1.SYS_PAIR_AUTH && it.status == R1.STATUS_ACK }
        if (observed.isEmpty()) {
            add("link", "Ring answers", CheckOutcome.FAIL, "Nothing received from the ring. Is it connected and off the charger?")
            return CheckReport(startedAt, null, null, items)
        }
        add("pairAuth", "Session (pairAuth)", if (pair != null) CheckOutcome.PASS else CheckOutcome.FAIL,
            if (pair != null) "Acknowledged" else "No acknowledgement; the ring may have changed how sessions open")

        // --- device info -----------------------------------------------------
        val info = replies(R1.MODULE_SYSTEM, 0, R1.SYS_DEVICE_INFO).firstNotNullOfOrNull { safeDecode(it) as? DeviceInfo }
        if (info == null || info.firmware.isBlank()) {
            add("deviceInfo", "Device info", CheckOutcome.FAIL, "No readable firmware version in the reply")
        } else {
            add("deviceInfo", "Device info", CheckOutcome.PASS, "Firmware ${info.firmware}" + (info.hardware?.let { ", hardware $it" } ?: ""))
            if (info.firmware in TESTED_FIRMWARE) {
                add("firmware", "Firmware version", CheckOutcome.PASS, "${info.firmware} is a version this app was tested with")
            } else {
                add("firmware", "Firmware version", CheckOutcome.INFO,
                    "${info.firmware} is newer or different from the tested ${TESTED_FIRMWARE.joinToString()}; check the results below")
            }
        }

        // --- battery ---------------------------------------------------------
        val status = replies(R1.MODULE_SYSTEM, 0, R1.SYS_DEVICE_STATUS).firstNotNullOfOrNull { safeDecode(it) as? DeviceStatus }
        when {
            status == null -> add("deviceStatus", "Battery", CheckOutcome.FAIL, "No readable reply")
            status.batteryPercent !in 0..100 -> add("deviceStatus", "Battery", CheckOutcome.WARN, "Battery ${status.batteryPercent} % is out of range; the layout may have changed")
            status.chargeState != null && status.chargeState !in 0..2 ->
                add("deviceStatus", "Battery", CheckOutcome.WARN, "${status.batteryPercent} %, unknown charge state ${status.chargeState}")
            else -> add("deviceStatus", "Battery", CheckOutcome.PASS,
                "${status.batteryPercent} %" + if (status.chargeState == 1) ", charging" else "")
        }

        // --- health: each request acked and its data plausible ----------------
        val tzOk = { tz: Int -> tz == tzOffsetMin }
        for (req in sent.filter { it.module == R1.MODULE_HEALTH }) {
            val name = R1.HEALTH_CMDS[req.cmd] ?: "cmd ${req.cmd}"
            val title = TITLES[req.cmd] ?: name
            val ack = ackFor(req)
            val data = pushes(R1.MODULE_HEALTH, req.cmd, R1.HEALTH_SUB_DAILY)
            if (req.cmd == R1.HEALTH_TEMPERATURE) {
                // Firmware 2.3.2 ignores this request; the nightly value comes in the sleep record.
                if (ack == null && data.isEmpty()) {
                    add("temperature", title, CheckOutcome.PASS, "No reply, as before (temperature comes with sleep)")
                } else {
                    add("temperature", title, CheckOutcome.WARN,
                        "Changed: the ring now answers" + if (data.isNotEmpty()) " with ${data.size} data frame(s) — worth decoding" else " (ack only)")
                }
                continue
            }
            add("ack:$name", "$title request", if (ack != null) CheckOutcome.PASS else CheckOutcome.FAIL,
                if (ack != null) "Acknowledged" else "No acknowledgement for sequence 0x%04X".format(req.seq))
            items.add(checkData("data:$name", title, req.cmd, data, nowSec, tzOk))
        }

        // --- reading interval ------------------------------------------------
        val gaps = readingTimes.zipWithNext { a, b -> b - a }.filter { it in 300..1200 }.sorted()
        when {
            !frequentUpdates -> add("interval", "Heart-rate reading interval", CheckOutcome.INFO, "Turn on Frequent updates to check this")
            gaps.size < 3 -> add("interval", "Heart-rate reading interval", CheckOutcome.INFO, "Not enough readings in the last day yet (${readingTimes.size})")
            else -> {
                val median = gaps[gaps.size / 2]
                val text = "Median ${median / 60} min ${median % 60} s over ${gaps.size} intervals"
                if (median in READING_INTERVAL_SEC) add("interval", "Heart-rate reading interval", CheckOutcome.PASS, "$text (expected about 9 min 45 s)")
                else add("interval", "Heart-rate reading interval", CheckOutcome.WARN, "$text; was 9 min 45 s. Frequent updates may poll at the wrong times")
            }
        }

        // --- anything new or broken --------------------------------------------
        val unknown = frames.filter { isUnknown(it) }.groupingBy { it.name }.eachCount()
        if (unknown.isEmpty()) add("unknown", "Unknown messages", CheckOutcome.PASS, "None")
        else add("unknown", "Unknown messages", CheckOutcome.INFO,
            unknown.entries.joinToString { "${it.key} ×${it.value}" } + " (new in this firmware, or from the Even app)")

        val badCrc = observed.count { it.frame != null && !it.frame.innerCrcOk && it.frame.fromRing }
        val errors = observed.mapNotNull { it.error }
        when {
            errors.isNotEmpty() -> add("integrity", "Frame format", CheckOutcome.WARN,
                "${errors.size} frame(s) could not be read: ${errors.distinct().take(3).joinToString("; ")}")
            badCrc > 0 -> add("integrity", "Frame format", CheckOutcome.WARN, "$badCrc frame(s) with an unexpected checksum")
            else -> add("integrity", "Frame format", CheckOutcome.PASS,
                "${frames.size} frames, all readable" + (observed.count { it.frame == null }.takeIf { it > 0 }?.let { "; $it other transfers ignored" } ?: ""))
        }

        return CheckReport(startedAt, info?.firmware, info?.hardware, items)
    }

    /**
     * Outcome changes between an earlier report and a newer one; empty if none.
     * A check the earlier run didn't have (added in an app update) counts only
     * if it doesn't pass.
     */
    fun compare(before: CheckReport, after: CheckReport): List<CheckChange> {
        val old = before.items.associateBy { it.id }
        val new = after.items.associateBy { it.id }
        return (old.keys + new.keys).distinct().mapNotNull { id ->
            val a = old[id]
            val b = new[id]
            if (a?.outcome == b?.outcome || (a == null && b?.outcome == CheckOutcome.PASS)) null
            else CheckChange(b?.title ?: a!!.title, a?.outcome, b?.outcome, b?.detail ?: "No longer checked")
        }
    }

    private val TITLES = mapOf(
        R1.HEALTH_HEART_RATE to "Heart rate", R1.HEALTH_SPO2 to "Blood oxygen", R1.HEALTH_HRV to "HRV",
        R1.HEALTH_ACTIVITY to "Activity", R1.HEALTH_SLEEP to "Sleep", R1.HEALTH_TEMPERATURE to "Temperature request",
    )

    private val RANGES = mapOf(
        R1.HEALTH_HEART_RATE to 25..230, R1.HEALTH_SPO2 to 70..100, R1.HEALTH_HRV to 1..300,
    )
    private val UNITS = mapOf(R1.HEALTH_HEART_RATE to "bpm", R1.HEALTH_SPO2 to "%", R1.HEALTH_HRV to "ms")

    private fun safeDecode(frame: Frame): Decoded? = try { decode(frame) } catch (e: IllegalArgumentException) { null }

    private fun isUnknown(f: Frame): Boolean = when {
        f.status !in R1.STATUSES -> true
        f.module == R1.MODULE_HEALTH -> f.cmd !in R1.HEALTH_CMDS || f.subCmd !in R1.HEALTH_SUBCMDS
        f.module == R1.MODULE_SYSTEM -> f.cmd != 0 || f.subCmd !in R1.SYSTEM_SUBCMDS
        else -> true
    }

    private fun checkData(id: String, title: String, cmd: Int, frames: List<Frame>, nowSec: Long, tzOk: (Int) -> Boolean): CheckItem {
        if (frames.isEmpty()) {
            return if (cmd == R1.HEALTH_SLEEP) CheckItem(id, "$title data", CheckOutcome.INFO, "No night waiting (normal once the Even app has synced it)")
            else CheckItem(id, "$title data", CheckOutcome.WARN, "No data sent")
        }
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val decoded = frames.mapNotNull { f ->
            try {
                decode(f).also { if (it == null) problems += "a ${f.data.size}-byte frame was not understood" }
            } catch (e: IllegalArgumentException) {
                problems += "unreadable: ${e.message}"
                null
            }
        }
        val future = nowSec + 3600
        for (d in decoded) when (d) {
            is Vitals -> {
                if (d.dayBase == null) problems += "day start not recognised"
                if (!tzOk(d.tzOffsetMin)) problems += "time zone ${d.tzOffsetMin} min differs from the phone's"
                val range = RANGES[d.cmd] ?: 0..Int.MAX_VALUE
                val bad = d.records.filter { it.hour > 23 || it.avg !in range || it.min > it.avg || it.avg > it.max }
                if (bad.isNotEmpty()) problems += "${bad.size} of ${d.records.size} hours out of range (e.g. hour ${bad[0].hour}: ${bad[0].min}/${bad[0].avg}/${bad[0].max})"
                if (d.records.any { (it.ts ?: 0) > future }) problems += "hours in the future"
                val good = d.records - bad.toSet()
                val unit = UNITS[d.cmd].orEmpty()
                notes += if (good.isEmpty()) "no hours" else "${good.size} hours, ${good.minOf { it.min }}–${good.maxOf { it.max }} $unit"
                if (d.cmd == R1.HEALTH_HEART_RATE && d.currentTs != null) {
                    val age = (nowSec - d.currentTs) / 60
                    if (d.current !in range) problems += "latest reading ${d.current} out of range"
                    else notes += "latest ${d.current} $unit, $age min ago"
                }
            }
            is Activity -> {
                if (d.dayBase == null) problems += "day start not recognised"
                if (!tzOk(d.tzOffsetMin)) problems += "time zone ${d.tzOffsetMin} min differs from the phone's"
                val bad = d.records.filter { it.slot >= 144 || it.steps > 3000 || it.kcalActive > it.kcalTotal }
                if (bad.isNotEmpty()) problems += "${bad.size} of ${d.records.size} blocks implausible (slot ${bad[0].slot}: ${bad[0].steps} steps)"
                if (d.records.any { (it.ts ?: 0) > future }) problems += "blocks in the future"
                notes += "${d.records.size} blocks, ${d.records.sumOf { it.steps }} steps"
            }
            is Sleep -> {
                val length = d.endTs - d.startTs
                if (length !in 600..18 * 3600) problems += "night length ${length / 60} min is implausible"
                if (d.endTs > future) problems += "night ends in the future"
                val staged = d.stages.sumOf { it.halfMinutes } * 30L
                if (staged > length + 600) problems += "stages (${staged / 60} min) longer than the night (${length / 60} min)"
                if (d.score !in 0..100 || d.efficiency !in 0..100) problems += "score ${d.score} or efficiency ${d.efficiency} out of range"
                if (d.skinTempC == null) notes += "no temperature"
                else if (d.skinTempC !in 25.0..40.0) problems += "temperature ${d.skinTempC} °C out of range"
                notes += "night of ${length / 3600} h ${length % 3600 / 60} min, score ${d.score}" + (d.skinTempC?.let { ", $it °C" } ?: "")
            }
            is SleepInterval -> notes += "interval record"
            else -> problems += "unexpected payload ${d::class.simpleName}"
        }
        val summary = (problems + notes).distinct().joinToString("; ")
        return CheckItem(id, "$title data", if (problems.isEmpty()) CheckOutcome.PASS else CheckOutcome.WARN, summary)
    }
}
