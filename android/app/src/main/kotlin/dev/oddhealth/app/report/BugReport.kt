package dev.oddhealth.app.report

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.oddhealth.app.BuildConfig
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Builds a link to the project's bug report form with version details filled
 * in, and copies debug logs to Downloads so they can be attached there.
 * GitHub can't take attachments through a link, so the user attaches the file.
 */
object BugReport {
    /** Field ids match .github/ISSUE_TEMPLATE/bug_report.yml. */
    fun issueUrl(firmware: String?): String {
        val fields = linkedMapOf(
            "template" to "bug_report.yml",
            "app-version" to BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (test build)" else "",
            "android" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "firmware" to (firmware ?: ""),
        )
        return "https://github.com/${BuildConfig.UPDATE_REPO}/issues/new?" +
            fields.entries.joinToString("&") { (k, v) -> "$k=${Uri.encode(v)}" }
    }

    /** Copy [zip] into the shared Downloads folder. Returns the file name used. */
    fun saveToDownloads(context: Context, zip: File): String {
        val name = "odd-health-debug-logs-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Couldn't create the file in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out -> zip.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        return name
    }
}
