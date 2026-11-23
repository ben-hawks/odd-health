package dev.oddhealth.app.debug

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Optional diagnostic log, off by default. When on, it records raw Bluetooth
 * traffic with the ring plus app events, one file per day, in a format that
 * `tools/r1tool.py decode` reads:
 *
 *     <time>  N  <characteristic>  <hex>     notification from the ring
 *     <time>  W  <characteristic>  <hex>     our write to the ring
 *     # <time> <text>                        events
 *
 * The files contain health data. They stay on the phone until the user shares
 * or deletes them.
 */
class DebugLog(private val context: Context, private val enabled: () -> Boolean) {
    private val lock = Any()
    private var writer: FileWriter? = null
    private var day: LocalDate? = null

    /**
     * Set while a ring check runs: its traffic and report are written even when
     * the log is off, because the user asked for that run.
     */
    @Volatile var forced = false

    private fun on() = forced || enabled()

    val dir: File get() = File(context.filesDir, "debug").apply { mkdirs() }

    fun files(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    fun raw(direction: String, characteristic: String, hex: String) {
        if (on()) write("${OffsetDateTime.now()}\t$direction\t$characteristic\t$hex")
    }

    fun event(text: String) {
        if (on()) write("# ${OffsetDateTime.now()} $text")
    }

    fun close() = synchronized(lock) {
        writer?.close()
        writer = null
        day = null
    }

    fun deleteAll() = synchronized(lock) {
        close()
        files().forEach { it.delete() }
    }

    /** Zip every log file into the cache directory, for sharing. */
    fun zip(): File? = synchronized(lock) {
        writer?.flush()
        val logs = files()
        if (logs.isEmpty()) return null
        val out = File(context.cacheDir, "odd-health-debug-logs.zip")
        ZipOutputStream(FileOutputStream(out)).use { z ->
            for (f in logs) {
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        out
    }

    private fun write(line: String) = synchronized(lock) {
        try {
            val today = LocalDate.now()
            if (writer == null || day != today) {
                writer?.close()
                val file = File(dir, "r1-$today.log")
                val fresh = !file.exists()
                writer = FileWriter(file, true)
                day = today
                if (fresh) writer?.write(header())
            }
            writer?.apply {
                write(line)
                write("\n")
                flush()
            }
        } catch (e: IOException) {
            // Logging must never break the app.
        }
    }

    private fun header(): String {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "# Odd Health $version, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"
    }
}
