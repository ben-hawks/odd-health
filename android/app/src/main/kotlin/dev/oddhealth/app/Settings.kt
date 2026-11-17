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
    }
}
