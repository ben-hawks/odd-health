package dev.oddhealth.app

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * What the service has seen, for the screen and for the capture file.
 * Main thread only.
 *
 * Capture file lines are tab-separated so `tools/r1tool.py decode` can read them:
 *
 *     <time>  N  <characteristic>  <hex>     notification from the ring
 *     <time>  W  <characteristic>  <hex>     our write to the ring
 *     # <time> <text>                        events and decoded summaries
 */
object RingLog {
    private const val MAX_LINES = 400
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")

    val lines = ArrayDeque<String>()
    var status = "Not started"
        private set
    var onChange: (() -> Unit)? = null

    private var writer: FileWriter? = null
    private var writerDay: LocalDate? = null

    fun captureDir(context: Context): File = File(context.filesDir, "captures").apply { mkdirs() }

    fun latestCapture(context: Context): File? =
        captureDir(context).listFiles()?.filter { it.isFile }?.maxByOrNull { it.name }

    fun status(text: String) {
        status = text
        onChange?.invoke()
    }

    /** A raw value seen on the link. direction is "N" or "W". */
    fun raw(context: Context, direction: String, characteristic: String, hex: String) {
        write(context, "${OffsetDateTime.now()}\t$direction\t$characteristic\t$hex")
    }

    /** A human-readable event; shown on screen and kept in the file as a comment. */
    fun event(context: Context, text: String) {
        val now = OffsetDateTime.now()
        write(context, "# $now $text")
        lines.addLast("${now.format(clock)}  $text")
        while (lines.size > MAX_LINES) lines.removeFirst()
        onChange?.invoke()
    }

    private fun write(context: Context, line: String) {
        try {
            val today = LocalDate.now()
            if (writer == null || writerDay != today) {
                writer?.close()
                writer = FileWriter(File(captureDir(context), "r1-$today.log"), true)
                writerDay = today
            }
            writer?.apply {
                write(line)
                write("\n")
                flush()
            }
        } catch (e: java.io.IOException) {
            lines.addLast("capture file error: ${e.message}")
        }
    }
}
