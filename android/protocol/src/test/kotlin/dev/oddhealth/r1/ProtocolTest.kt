package dev.oddhealth.r1

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    /** Build ring -> phone notifications (MODBUS inner CRC), optionally fragmented. */
    private fun ringFrame(module: Int, cmd: Int, sub: Int, status: Int, seq: Int, data: ByteArray, chunk: Int? = null): List<ByteArray> {
        val inner = ByteArray(12 + data.size)
        inner[0] = 0x64; inner[1] = module.toByte(); inner[2] = 0x64
        inner[3] = seq.toByte(); inner[4] = (seq ushr 8).toByte()
        inner[5] = status.toByte(); inner[6] = cmd.toByte(); inner[7] = sub.toByte()
        inner[8] = inner.size.toByte(); inner[9] = (inner.size ushr 8).toByte()
        data.copyInto(inner, 12)
        val crc16 = crc16Modbus(inner)
        inner[10] = crc16.toByte(); inner[11] = (crc16 ushr 8).toByte()
        val crc32 = crc32Ring(inner)
        val head = ByteArray(4) { (crc32 ushr (8 * it)).toByte() }
        val slices = if (chunk == null) listOf(inner) else inner.toList().chunked(chunk).map { it.toByteArray() }
        return slices.mapIndexed { i, s -> byteArrayOf((slices.size - 1 - i).toByte()) + head + s }
    }

    @Test
    fun builtFramesMatchPythonTool() {
        // Same bytes as `tools/r1tool.py frames`, which the ring accepted.
        assertEquals("008e8ed0a464016402000000020c0070c9", systemRequest(R1.SYS_DEVICE_INFO, 2).toHex())
        assertEquals("000cd82f7964016403000000010c0081cc", systemRequest(R1.SYS_DEVICE_STATUS, 3).toHex())
        assertEquals("00dff6e79b64026404000001010c007040", healthRequest(R1.HEALTH_HEART_RATE, 4).toHex())
        assertEquals("00352eb69264026408000006010c00213d", healthRequest(R1.HEALTH_SLEEP, 8).toHex())
    }

    @Test
    fun realRingFramesParse() {
        // Notifications captured from a ring on firmware 2.3.2.0008.
        val reasm = Reassembler()
        val ack = parseInner(reasm.feed("00a25aac92640164010003000 80d00a76400".replace(" ", "").hexToBytes())!!)
        assertEquals("system/pairAuth ack", ack.name)
        assertTrue(ack.innerCrcOk)
        assertEquals(1, ack.seq)

        val status = parseInner(reasm.feed("00b8de722b640164030003000113001 16a29020100000000".replace(" ", "").hexToBytes())!!)
        assertTrue(status.innerCrcOk)
        assertEquals(DeviceStatus(41, 2), decode(status))

        val info = parseInner(
            reasm.feed("00ae2d1f216401640200030002 2c003d91322e332e322e303030380000000000003630334d56312e392e33000000000000".replace(" ", "").hexToBytes())!!,
        )
        assertEquals(DeviceInfo("2.3.2.0008", "603MV1.9.3"), decode(info))
    }

    @Test
    fun dangerousCommandsRefused() {
        for (sub in R1.DANGEROUS_SYSTEM_SUBCMDS) {
            assertFailsWith<IllegalArgumentException> { buildFrame(1, 0, sub, R1.STATUS_SET, 1) }
        }
    }

    @Test
    fun fragmentsReassemble() {
        val data = ByteArray(200) { it.toByte() }
        val notifications = ringFrame(2, 5, 1, R1.STATUS_PUSH, 9, data, chunk = 60)
        assertTrue(notifications.size > 1)
        val reasm = Reassembler()
        val results = notifications.map { reasm.feed(it) }
        assertTrue(results.dropLast(1).all { it == null })
        assertContentEquals(data, parseInner(results.last()!!).data)
    }

    @Test
    fun missingFragmentDetected() {
        val notifications = ringFrame(2, 5, 1, R1.STATUS_PUSH, 9, ByteArray(200), chunk = 60)
        val reasm = Reassembler()
        assertFailsWith<IllegalArgumentException> { notifications.drop(1).forEach { reasm.feed(it) } }
    }

    @Test
    fun packetAckPayload() {
        val push = parseInner(ringFrame(2, 2, 1, R1.STATUS_PUSH, 0x1234, byteArrayOf(0))[0].copyOfRange(5, 18))
        val ack = parseInner(packetAck(push, 7).let { it.copyOfRange(5, it.size) })
        assertEquals("system/packetAck set", ack.name)
        assertEquals("02020100341200000000", ack.data.toHex())
    }

    @Test
    fun heartRateVector() {
        val frame = parseInner(
            ringFrame(2, 1, 1, R1.STATUS_PUSH, 1, "03000000000000da6200006a0449583b05697a570668715800000000".hexToBytes())[0]
                .let { it.copyOfRange(5, it.size) },
        )
        val v = decode(frame) as Vitals
        assertEquals(106, v.current)
        assertEquals(listOf(Triple(4, 73, 59), Triple(5, 105, 87), Triple(6, 104, 88)), v.records.map { Triple(it.hour, it.avg, it.min) })
        assertNull(v.records[0].ts)
    }

    @Test
    fun hrvVectorAndAnchoring() {
        val tz = -360
        val base = 1_800_000_000L - Math.floorMod(1_800_000_000L + tz * 60L, 86400L)
        val data = ByteArray(13 + 7)
        data[0] = 1
        data[1] = tz.toByte(); data[2] = (tz ushr 8).toByte()
        for (i in 0..3) data[3 + i] = (base ushr (8 * i)).toByte()
        for (i in 0..3) data[7 + i] = ((base + 100) ushr (8 * i)).toByte()
        data[11] = 39
        data[13] = 5; data[14] = 0x2C; data[15] = 0x01; data[17] = 0x01; data[19] = 0x01
        val v = decode(parseInner(ringFrame(2, 4, 1, R1.STATUS_PUSH, 1, data)[0].let { it.copyOfRange(5, it.size) })) as Vitals
        assertEquals(39, v.current)
        assertEquals(base + 100, v.currentTs)
        assertEquals(HourRecord(5, 300, 256, 256, base + 5 * 3600), v.records[0])
    }

    @Test
    fun activityAndSleep() {
        val base = 1_800_000_000L - 1_800_000_000L % 86400
        val act = ByteArray(14)
        act[0] = 1
        for (i in 0..3) act[3 + i] = (base ushr (8 * i)).toByte()
        act[7] = 71; act[10] = 3; act[12] = 15
        val a = decode(parseInner(ringFrame(2, 5, 1, R1.STATUS_PUSH, 1, act)[0].let { it.copyOfRange(5, it.size) })) as Activity
        assertEquals(ActivityRecord(71, 0, 3, 15, base + 71 * 600), a.records[0])

        val sleep = ByteArray(32 + 6 + 4)
        sleep[0] = 1; sleep[1] = 90; sleep[2] = 82
        sleep[8] = 0x60; sleep[9] = 0x01 // 35.2 C
        sleep[12] = 0xE8.toByte(); sleep[13] = 0x03 // start 1000
        sleep[16] = 0x10; sleep[17] = 0x09 // end 2320
        sleep[30] = 2
        sleep[32] = 2; sleep[33] = 24
        sleep[35] = 3; sleep[36] = 12
        val s = decode(parseInner(ringFrame(2, 6, 1, R1.STATUS_PUSH, 1, sleep)[0].let { it.copyOfRange(5, it.size) })) as Sleep
        assertEquals(35.2, s.skinTempC)
        assertEquals(1000L to 2320L, s.startTs to s.endTs)
        assertEquals(listOf(SleepStage(2, 24), SleepStage(3, 12)), s.stages)
    }
}
