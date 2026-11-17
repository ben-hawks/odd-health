package dev.oddhealth.app.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import dev.oddhealth.app.Settings
import dev.oddhealth.app.data.HealthRepository
import dev.oddhealth.app.debug.DebugLog
import dev.oddhealth.app.ui.model.HealthConnectState
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.sync.SyncEngine
import dev.oddhealth.core.sync.SyncOutcome
import dev.oddhealth.core.sync.SyncTarget
import dev.oddhealth.healthconnect.HealthConnectTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Runs syncs to every target. Today that is Health Connect; another service
 * would be one more [SyncTarget] in [targets].
 */
class SyncManager(
    private val context: Context,
    private val repository: HealthRepository,
    private val settings: Settings,
    private val log: DebugLog,
    private val scope: CoroutineScope,
) {
    val healthConnect = HealthConnectTarget(context)
    private val targets: List<SyncTarget> = listOf(healthConnect)
    private val engine = SyncEngine(repository, repository)
    private val mutex = Mutex()
    /** Sync requests, each carrying how long to wait for more data first. */
    private val requests = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        // Requests arriving within the delay collapse into one sync. A request
        // made while a sync is running waits for it rather than cancelling it.
        @OptIn(FlowPreview::class)
        scope.launch {
            requests.debounce { it }.collect {
                try {
                    syncNow()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    log.event("Sync failed: $e")
                }
            }
        }
    }

    private val _state = MutableStateFlow(HealthConnectState.NEEDS_PERMISSION)
    val state: StateFlow<HealthConnectState> = _state.asStateFlow()

    /** Kinds Health Connect currently accepts (permissions granted). */
    private val _writable = MutableStateFlow<Set<RecordKind>>(emptySet())
    val writable: StateFlow<Set<RecordKind>> = _writable.asStateFlow()

    /** Ledger scope per writable kind; see [SyncTarget.ledgerScope]. */
    private val _scopes = MutableStateFlow<Map<RecordKind, String>>(emptyMap())
    val scopes: StateFlow<Map<RecordKind, String>> = _scopes.asStateFlow()

    private val _skinSupported = MutableStateFlow(true)
    val skinSupported: StateFlow<Boolean> = _skinSupported.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Re-read availability and permissions; call on resume and after a permission request. */
    suspend fun refresh() {
        _state.value = when (HealthConnectTarget.sdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                val kinds = healthConnect.writableKinds()
                _writable.value = kinds
                _skinSupported.value = healthConnect.skinSupported
                _scopes.value = kinds.associateWith(healthConnect::ledgerScope)
                if (kinds.isEmpty()) HealthConnectState.NEEDS_PERMISSION else HealthConnectState.READY
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectState.NOT_INSTALLED
            else -> HealthConnectState.UNSUPPORTED
        }
    }

    /** Sync shortly; repeated calls within the delay collapse into one sync. */
    fun syncSoon(delayMillis: Long = 20_000) {
        requests.tryEmit(delayMillis)
    }

    suspend fun syncNow(): List<SyncOutcome> = mutex.withLock {
        _syncing.value = true
        try {
            refresh()
            targets.map { target ->
                val outcome = try {
                    engine.sync(target)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    SyncOutcome.Skipped(target.displayName, e.toString())
                }
                log.event("Sync: $outcome")
                outcome
            }.also {
                if (it.any { o -> o is SyncOutcome.Done }) settings.setLastSync(Instant.now())
            }
        } finally {
            _syncing.value = false
        }
    }
}
