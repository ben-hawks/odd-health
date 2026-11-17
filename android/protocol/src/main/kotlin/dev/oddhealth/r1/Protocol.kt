package dev.oddhealth.r1

/**
 * Even R1 ring binary health channel: framing. See docs/protocol.md.
 *
 *     notification / write = [fragIndex u8][crc32 u32 LE][slice of inner frame]
 *     inner frame          = [0x64][module][0x64][seq u16][status][cmd][subCmd]
 *                            [innerLen u16][crc16 u16][data...]
 */
object R1 {
    const val SERVICE_UUID = "bae80001-4f05-4503-8e65-3af1f7329d1f"
    const val WRITE_CHAR_UUID = "bae80012-4f05-4503-8e65-3af1f7329d1f"
    const val NOTIFY_CHAR_UUID = "bae80013-4f05-4503-8e65-3af1f7329d1f"
    const val STATUS_NOTIFY_CHAR_UUID = "bae80011-4f05-4503-8e65-3af1f7329d1f"

    const val MARKER = 0x64
    const val HEADER_LEN = 12

    const val MODULE_SYSTEM = 1
    const val MODULE_HEALTH = 2

    const val STATUS_REQ = 0
    const val STATUS_SET = 1
    const val STATUS_PUSH = 2
    const val STATUS_ACK = 3

    const val HEALTH_HEART_RATE = 1
    const val HEALTH_SPO2 = 2
    const val HEALTH_TEMPERATURE = 3
    const val HEALTH_HRV = 4
    const val HEALTH_ACTIVITY = 5
    const val HEALTH_SLEEP = 6
    const val HEALTH_SUB_DAILY = 1

    const val SYS_DEVICE_STATUS = 0x01
    const val SYS_DEVICE_INFO = 0x02
    const val SYS_WEAR_STATUS = 0x03
    const val SYS_HEARTBEAT = 0x7F
    const val SYS_PAIR_AUTH = 0x08
    const val SYS_PACKET_ACK = 0x7E

    val MODULES = mapOf(1 to "system", 2 to "health", 3 to "sport")
    val STATUSES = mapOf(0 to "req", 1 to "set", 2 to "push", 3 to "ack")
    val HEALTH_CMDS = mapOf(
        1 to "heartRate", 2 to "spo2", 3 to "temperature", 4 to "hrv", 5 to "activity", 6 to "sleep",
    )
    val HEALTH_SUBCMDS = mapOf(1 to "daily", 2 to "point", 3 to "measure")
    val SYSTEM_SUBCMDS = mapOf(
        0x01 to "deviceStatus", 0x02 to "deviceInfo", 0x03 to "wearStatus", 0x04 to "userInfo",
        0x05 to "systemTime", 0x07 to "touchSwitch", 0x08 to "pairAuth", 0x09 to "otaStart",
        0x0A to "advStart", 0x0B to "getAlgoKeyStatus", 0x0C to "setAlgoKey",
        0x0E to "healthSettingsStatus", 0x0F to "systemSettingsStatus", 0x10 to "deviceSerial",
        0x11 to "nvRecover", 0x12 to "powerControl", 0x13 to "pairDelete", 0x7E to "packetAck",
        0x7F to "heartbeatPack", 0x82 to "removeRingNotify",
    )

    /**
     * System sub-commands that change pairing, firmware, keys, stored data, power
     * state or the ring's link to the glasses. [buildFrame] refuses them.
     */
    val DANGEROUS_SYSTEM_SUBCMDS = setOf(0x07, 0x09, 0x0A, 0x0C, 0x11, 0x12, 0x13, 0x82)

    /** The official app's session-open frame, captured verbatim (seq 1, data 01). */
    val PAIR_AUTH_FRAME: ByteArray = "00971953f964016401000000080d003f0101".hexToBytes()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.i16(i: Int): Int = u16(i).toShort().toInt()
internal fun ByteArray.u32(i: Int): Long =
    u16(i).toLong() or (u16(i + 2).toLong() shl 16)

private val CRC32_TABLE = IntArray(256) { i ->
    var c = i shl 24
    repeat(8) { c = if (c < 0) (c shl 1) xor 0x1EDC6F41 else c shl 1 }
    c
}

/** Transport CRC: Castagnoli polynomial, MSB-first, init 0, no final xor. */
fun crc32Ring(data: ByteArray, from: Int = 0): Int {
    var c = 0
    for (i in from until data.size) {
        c = (c shl 8) xor CRC32_TABLE[((c ushr 24) xor data.u8(i)) and 0xFF]
    }
    return c
}

private fun crcByte(inner: ByteArray, i: Int): Int = if (i == 10 || i == 11) 0 else inner.u8(i)

/** Inner CRC on ring -> phone frames, computed with the crc slot as zero. */
fun crc16Modbus(inner: ByteArray): Int {
    var c = 0xFFFF
    for (i in inner.indices) {
        c = c xor crcByte(inner, i)
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
    }
    return c
}

/** Inner CRC we put on phone -> ring frames; the ring does not check it. */
fun crc16CcittFalse(inner: ByteArray): Int {
    var c = 0xFFFF
    for (i in inner.indices) {
        c = c xor (crcByte(inner, i) shl 8)
        repeat(8) { c = (if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1) and 0xFFFF }
    }
    return c
}

class Frame(
    val module: Int,
    val seq: Int,
    val status: Int,
    val cmd: Int,
    val subCmd: Int,
    val data: ByteArray,
    /** MODBUS check; true on genuine ring -> phone frames. */
    val innerCrcOk: Boolean,
) {
    val fromRing: Boolean get() = status == R1.STATUS_PUSH || status == R1.STATUS_ACK

    val name: String
        get() {
            val mod = R1.MODULES[module] ?: "mod$module"
            val what = when {
                module == R1.MODULE_HEALTH ->
                    "${R1.HEALTH_CMDS[cmd] ?: "0x%02x".format(cmd)}/${R1.HEALTH_SUBCMDS[subCmd] ?: "0x%02x".format(subCmd)}"
                module == R1.MODULE_SYSTEM && cmd == 0 -> R1.SYSTEM_SUBCMDS[subCmd] ?: "0x%02x".format(subCmd)
                else -> "0x%02x/0x%02x".format(cmd, subCmd)
            }
            return "$mod/$what ${R1.STATUSES[status] ?: "status $status"}"
        }
}

fun parseInner(inner: ByteArray): Frame {
    require(inner.size >= R1.HEADER_LEN) { "inner frame too short (${inner.size} bytes)" }
    require(inner.u8(0) == R1.MARKER && inner.u8(2) == R1.MARKER) { "bad frame markers" }
    val length = inner.u16(8)
    require(length == inner.size) { "declared length $length != actual ${inner.size}" }
    return Frame(
        module = inner.u8(1),
        seq = inner.u16(3),
        status = inner.u8(5),
        cmd = inner.u8(6),
        subCmd = inner.u8(7),
        data = inner.copyOfRange(R1.HEADER_LEN, inner.size),
        innerCrcOk = inner.u16(10) == crc16Modbus(inner),
    )
}

/** Build one single-fragment phone -> ring frame. */
fun buildFrame(module: Int, cmd: Int, subCmd: Int, status: Int, seq: Int, data: ByteArray = ByteArray(0)): ByteArray {
    require(!(module == R1.MODULE_SYSTEM && cmd == 0 && subCmd in R1.DANGEROUS_SYSTEM_SUBCMDS)) {
        "refusing to build ${R1.SYSTEM_SUBCMDS[subCmd]}: mutates ring state"
    }
    val length = R1.HEADER_LEN + data.size
    val inner = ByteArray(length)
    inner[0] = R1.MARKER.toByte()
    inner[1] = module.toByte()
    inner[2] = R1.MARKER.toByte()
    inner[3] = seq.toByte()
    inner[4] = (seq ushr 8).toByte()
    inner[5] = status.toByte()
    inner[6] = cmd.toByte()
    inner[7] = subCmd.toByte()
    inner[8] = length.toByte()
    inner[9] = (length ushr 8).toByte()
    data.copyInto(inner, R1.HEADER_LEN)
    val crc16 = crc16CcittFalse(inner)
    inner[10] = crc16.toByte()
    inner[11] = (crc16 ushr 8).toByte()
    val crc32 = crc32Ring(inner)
    val out = ByteArray(5 + length)
    for (i in 0..3) out[1 + i] = (crc32 ushr (8 * i)).toByte()
    inner.copyInto(out, 5)
    return out
}

/** A bare read request for one health metric's daily data. */
fun healthRequest(cmd: Int, seq: Int): ByteArray =
    buildFrame(R1.MODULE_HEALTH, cmd, R1.HEALTH_SUB_DAILY, R1.STATUS_REQ, seq)

fun systemRequest(subCmd: Int, seq: Int): ByteArray =
    buildFrame(R1.MODULE_SYSTEM, 0, subCmd, R1.STATUS_REQ, seq)

/** Acknowledge a received health push; marks it synced and pulls the next batch. */
fun packetAck(received: Frame, seq: Int): ByteArray {
    val payload = ByteArray(10)
    payload[0] = received.module.toByte()
    payload[1] = received.cmd.toByte()
    payload[2] = received.subCmd.toByte()
    payload[4] = received.seq.toByte()
    payload[5] = (received.seq ushr 8).toByte()
    return buildFrame(R1.MODULE_SYSTEM, 0, R1.SYS_PACKET_ACK, R1.STATUS_SET, seq, payload)
}

/**
 * Feed raw notifications in arrival order; returns a complete inner frame when
 * the last fragment (index 0) arrives. Fragments of one batch share bytes 1..4,
 * the CRC-32 of the whole inner frame.
 */
class Reassembler {
    private var batch: Int? = null
    private val parts = ArrayList<Pair<Int, ByteArray>>()

    fun reset() {
        batch = null
        parts.clear()
    }

    /** A reassembled batch. crcOk is false if a fragment was lost or the data is not a health frame. */
    class Batch(val inner: ByteArray, val crcOk: Boolean)

    /** Like [feed] but reports a failed checksum instead of throwing. */
    fun feedLenient(notification: ByteArray): Batch? {
        require(notification.size >= 5) { "fragment too short" }
        val index = notification.u8(0)
        val id = notification.u32(1).toInt()
        if (id != batch) {
            batch = id
            parts.clear()
        }
        parts.add(index to notification.copyOfRange(5, notification.size))
        if (index != 0) return null
        val ordered = parts.sortedByDescending { it.first }
        reset()
        val inner = ByteArray(ordered.sumOf { it.second.size })
        var off = 0
        for ((_, part) in ordered) {
            part.copyInto(inner, off)
            off += part.size
        }
        return Batch(inner, crc32Ring(inner) == id)
    }

    fun feed(notification: ByteArray): ByteArray? {
        val result = feedLenient(notification) ?: return null
        require(result.crcOk) { "batch CRC mismatch (missing fragment?)" }
        return result.inner
    }
}
