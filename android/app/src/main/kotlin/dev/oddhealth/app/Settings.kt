package dev.oddhealth.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** Small persistent settings, exposed as flows for the UI. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _stayConnected = MutableStateFlow(prefs.getBoolean(STAY_CONNECTED, true))
    val stayConnected: StateFlow<Boolean> = _stayConnected.asStateFlow()

    private val _debugLogging = MutableStateFlow(prefs.getBoolean(DEBUG_LOGGING, false))
    val debugLogging: StateFlow<Boolean> = _debugLogging.asStateFlow()

    private val _lastSync = MutableStateFlow(prefs.getLong(LAST_SYNC, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    val lastSync: StateFlow<Instant?> = _lastSync.asStateFlow()

    /** Minutes between our own heart-rate requests; 0 = off. */
    private val _hrPollMinutes = MutableStateFlow(prefs.getInt(HR_POLL, 0))
    val hrPollMinutes: StateFlow<Int> = _hrPollMinutes.asStateFlow()

    fun setHrPollMinutes(minutes: Int) {
        prefs.edit().putInt(HR_POLL, minutes).apply()
        _hrPollMinutes.value = minutes
    }

    private val _noticeAccepted = MutableStateFlow(prefs.getBoolean(NOTICE, false))
    val noticeAccepted: StateFlow<Boolean> = _noticeAccepted.asStateFlow()

    fun acceptNotice() {
        prefs.edit().putBoolean(NOTICE, true).apply()
        _noticeAccepted.value = true
    }

    private val _updateNotifications = MutableStateFlow(prefs.getBoolean(UPDATE_NOTIFY, true))
    val updateNotifications: StateFlow<Boolean> = _updateNotifications.asStateFlow()

    fun setUpdateNotifications(on: Boolean) {
        prefs.edit().putBoolean(UPDATE_NOTIFY, on).apply()
        _updateNotifications.value = on
    }

    private val _lastUpdateCheck = MutableStateFlow(prefs.getLong(UPDATE_CHECK, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli))
    val lastUpdateCheck: StateFlow<Instant?> = _lastUpdateCheck.asStateFlow()

    fun setLastUpdateCheck(at: Instant) {
        prefs.edit().putLong(UPDATE_CHECK, at.toEpochMilli()).apply()
        _lastUpdateCheck.value = at
    }

    /** The newest version a notification was shown for, so each is announced once. */
    var lastNotifiedVersion: String?
        get() = prefs.getString(UPDATE_NOTIFIED, null)
        set(value) = prefs.edit().putString(UPDATE_NOTIFIED, value).apply()

    var firmware: String?
        get() = prefs.getString(FIRMWARE, null)
        set(value) = prefs.edit().putString(FIRMWARE, value).apply()

    fun setStayConnected(on: Boolean) {
        prefs.edit().putBoolean(STAY_CONNECTED, on).apply()
        _stayConnected.value = on
    }

    fun setDebugLogging(on: Boolean) {
        prefs.edit().putBoolean(DEBUG_LOGGING, on).apply()
        _debugLogging.value = on
    }

    fun setLastSync(at: Instant) {
        prefs.edit().putLong(LAST_SYNC, at.toEpochMilli()).apply()
        _lastSync.value = at
    }

    private companion object {
        const val STAY_CONNECTED = "stay_connected"
        const val DEBUG_LOGGING = "debug_logging"
        const val LAST_SYNC = "last_sync"
        const val FIRMWARE = "firmware"
        const val HR_POLL = "hr_poll_minutes"
        const val NOTICE = "notice_accepted_v1"
        const val UPDATE_NOTIFY = "update_notifications"
        const val UPDATE_CHECK = "update_last_check"
        const val UPDATE_NOTIFIED = "update_last_notified"
    }
}
