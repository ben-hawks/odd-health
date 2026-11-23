package dev.oddhealth.app.ring

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.oddhealth.app.MainActivity
import dev.oddhealth.app.R
import dev.oddhealth.r1.CheckChange
import dev.oddhealth.r1.CheckOutcome
import dev.oddhealth.r1.CheckReport
import dev.oddhealth.r1.RingCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A finished check run and what changed since the previous run. */
data class CheckResult(val report: CheckReport, val previous: CheckReport?, val changes: List<CheckChange>)

/**
 * Results of the ring checks ([RingCheck]), run by [RingService]. Keeps the
 * last few reports in private storage so each run can be compared with the
 * one before, which is how a firmware update's effect shows.
 */
class RingChecks(private val context: Context) {
    companion object {
        const val EXTRA_OPEN_CHECKS = "open_checks"
        private const val CHANNEL = "checks"
        private const val NOTIFICATION_ID = 3
        private const val KEEP = 10
    }

    private val dir get() = File(context.filesDir, "checks").apply { mkdirs() }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _latest = MutableStateFlow(load())
    val latest: StateFlow<CheckResult?> = _latest.asStateFlow()

    /** True while the results dialog should be shown. */
    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    fun show() { if (_latest.value != null) _showing.value = true }
    fun dismiss() { _showing.value = false }

    internal fun started() { _running.value = true }

    /** Store a finished run, show it, and notify when it ran on its own (after a firmware change). */
    internal fun finished(report: CheckReport, automatic: Boolean) {
        val previous = _latest.value?.report
        File(dir, "check-${report.startedAt}.txt").writeText(report.serialize())
        reports().dropLast(KEEP).forEach { it.delete() }
        _latest.value = CheckResult(report, previous, previous?.let { RingCheck.compare(it, report) }.orEmpty())
        _running.value = false
        _showing.value = true
        if (automatic) notifyDone(report)
    }

    private fun reports() = dir.listFiles { f -> f.name.startsWith("check-") }?.sortedBy { it.name.removePrefix("check-").removeSuffix(".txt").toLongOrNull() ?: 0 }.orEmpty()

    private fun load(): CheckResult? {
        val parsed = reports().takeLast(2).mapNotNull { runCatching { CheckReport.parse(it.readText()) }.getOrNull() }
        val report = parsed.lastOrNull() ?: return null
        val previous = parsed.getOrNull(parsed.size - 2)
        return CheckResult(report, previous, previous?.let { RingCheck.compare(it, report) }.orEmpty())
    }

    private fun notifyDone(report: CheckReport) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.checks_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_CHECKS, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val problems = report.count(CheckOutcome.WARN) + report.count(CheckOutcome.FAIL)
        nm.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(context.getString(R.string.checks_notify_title, report.firmware ?: "?"))
                .setContentText(
                    if (problems == 0) context.getString(R.string.checks_notify_ok)
                    else context.resources.getQuantityString(R.plurals.checks_notify_problems, problems, problems),
                )
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
