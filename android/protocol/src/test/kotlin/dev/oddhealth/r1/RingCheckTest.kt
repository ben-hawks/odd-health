package dev.oddhealth.r1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RingCheckTest {
    private val tz = -360
    // 2026-11-17 00:00 at UTC-6, and noon that day.
    private val dayBase = 1_794_895_200L
    private val now = dayBase + 12 * 3600

    private fun frame(module: Int, cmd: Int, sub: Int, status: Int, seq: Int, data: ByteArray = ByteArray(0)) =
        Frame(module, seq, status, cmd, sub, data, innerCrcOk = true)

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }

    /** Heart-rate / SpO2 daily payload: hours 0..n-1 with min/avg/max. */
    private fun vitals(hours: List<Triple<Int, Int, Int>>, current: Int, currentTs: Long): ByteArray {
        var out = byteArrayOf(hours.size.toByte()) + le16(tz) + le32(dayBase) + le32(currentTs) + byteArrayOf(current.toByte())
        hours.forEachIndexed { h, (min, avg, max) -> out += byteArrayOf(h.toByte(), avg.toByte(), max.toByte(), min.toByte()) }
        return out
    }

    private fun sent(): List<SentRequest> {
        var seq = 0x4F10
        return listOf(SentRequest("pairAuth", 1, 0, R1.SYS_PAIR_AUTH, 1)) +
            RingCheck.REQUESTS.map { (label, module, code) ->
                if (module == R1.MODULE_SYSTEM) SentRequest(label, module, 0, code, seq++) else SentRequest(label, module, code, R1.HEALTH_SUB_DAILY, seq++)
            }
    }

    /** What firmware 2.3.2.0008 sends for a check run. */
    private fun healthyReplies(sent: List<SentRequest>): MutableList<Observed> {
        val out = mutableListOf<Observed>()
        for (r in sent) {
            if (r.module == R1.MODULE_HEALTH && r.cmd == R1.HEALTH_TEMPERATURE) continue // ignored by the ring
            val data = when (r.label) {
                "deviceInfo" -> "2.3.2.0008".toByteArray().copyOf(16) + "603MV1.9.3".toByteArray().copyOf(16)
                "deviceStatus" -> byteArrayOf(80, 0)
                else -> ByteArray(0)
            }
            out += Observed(frame(r.module, r.cmd, r.subCmd, R1.STATUS_ACK, r.seq, data), true)
        }
        val hr = vitals(List(12) { Triple(55, 62, 80) }, 64, now - 300)
        out += Observed(frame(2, R1.HEALTH_HEART_RATE, 1, R1.STATUS_PUSH, 900, hr), true)
        val spo2 = vitals(List(12) { Triple(94, 97, 99) }, 97, now - 1800)
        out += Observed(frame(2, R1.HEALTH_SPO2, 1, R1.STATUS_PUSH, 901, spo2), true)
        var hrv = byteArrayOf(2) + le16(tz) + le32(dayBase) + le32(now - 1800) + le16(45)
        repeat(2) { h -> hrv += byteArrayOf(h.toByte()) + le16(45) + le16(70) + le16(30) }
        out += Observed(frame(2, R1.HEALTH_HRV, 1, R1.STATUS_PUSH, 902, hrv), true)
        var act = byteArrayOf(2) + le16(tz) + le32(dayBase)
        repeat(2) { s -> act += byteArrayOf(s.toByte()) + le16(120) + le16(5) + le16(12) }
        out += Observed(frame(2, R1.HEALTH_ACTIVITY, 1, R1.STATUS_PUSH, 903, act), true)
        out += Observed(null, false) // an Even app transfer on the same channel
        return out
    }

    private val readings = List(20) { now - 600 * 20 + it * 588L }

    private fun run(observed: List<Observed>, sent: List<SentRequest> = sent()) =
        RingCheck.evaluate(0, sent, observed, now, tz, readings, frequentUpdates = true)

    private fun CheckReport.outcome(id: String) = items.first { it.id == id }.outcome

    @Test
    fun knownFirmwarePasses() {
        val s = sent()
        val report = run(healthyReplies(s), s)
        assertEquals("2.3.2.0008", report.firmware)
        assertEquals(0, report.count(CheckOutcome.FAIL), report.lines().joinToString("\n"))
        assertEquals(0, report.count(CheckOutcome.WARN), report.lines().joinToString("\n"))
        assertEquals(CheckOutcome.INFO, report.outcome("data:sleep"))
        assertEquals(CheckOutcome.PASS, report.outcome("temperature"))
        assertEquals(CheckOutcome.PASS, report.outcome("interval"))
    }

    @Test
    fun changedBehaviourIsFlagged() {
        val s = sent()
        val observed = healthyReplies(s)
        // New firmware: different version, an out-of-range heart rate, a temperature reply and a new message.
        observed.replaceAll { o ->
            val f = o.frame
            if (f != null && f.subCmd == R1.SYS_DEVICE_INFO && f.module == 1) {
                Observed(frame(1, 0, R1.SYS_DEVICE_INFO, R1.STATUS_ACK, f.seq, "2.4.0.0001".toByteArray().copyOf(32)), true)
            } else if (f != null && f.module == 2 && f.cmd == R1.HEALTH_HEART_RATE && f.status == R1.STATUS_PUSH) {
                Observed(frame(2, 1, 1, R1.STATUS_PUSH, f.seq, vitals(listOf(Triple(55, 250, 255)), 64, now - 300)), true)
            } else o
        }
        val temp = s.first { it.cmd == R1.HEALTH_TEMPERATURE && it.module == 2 }
        observed += Observed(frame(2, 3, 1, R1.STATUS_ACK, temp.seq), true)
        observed += Observed(frame(2, 9, 1, R1.STATUS_PUSH, 950, byteArrayOf(1, 2)), true)
        val report = run(observed, s)
        assertEquals(CheckOutcome.INFO, report.outcome("firmware"))
        assertEquals(CheckOutcome.WARN, report.outcome("data:heartRate"))
        assertEquals(CheckOutcome.WARN, report.outcome("temperature"))
        assertEquals(CheckOutcome.INFO, report.outcome("unknown"))

        val before = run(healthyReplies(sent()))
        val changes = RingCheck.compare(before, report).associateBy { it.title }
        assertEquals(CheckOutcome.WARN, changes.getValue("Heart rate data").after)
        assertEquals(CheckOutcome.PASS, changes.getValue("Heart rate data").before)
    }

    @Test
    fun missingAcksFail() {
        val s = sent()
        val observed = healthyReplies(s).filterNot { it.frame?.status == R1.STATUS_ACK && it.frame?.module == 2 }
        val report = run(observed, s)
        assertEquals(CheckOutcome.FAIL, report.outcome("ack:heartRate"))
        assertEquals(CheckOutcome.PASS, report.outcome("pairAuth"))
    }

    @Test
    fun silentRingFails() {
        val report = run(emptyList())
        assertEquals(listOf(CheckOutcome.FAIL), report.items.map { it.outcome })
    }

    @Test
    fun reportsRoundTrip() {
        val report = run(healthyReplies(sent()))
        val back = assertNotNull(CheckReport.parse(report.serialize()))
        assertEquals(report, back)
        assertTrue(RingCheck.compare(report, back).isEmpty())
        // Checks added since the earlier run are only listed when they don't pass.
        val older = report.copy(items = report.items.filterNot { it.id == "pairAuth" || it.id == "data:sleep" })
        assertEquals(listOf("Sleep data"), RingCheck.compare(older, report).map { it.title })
    }
}
