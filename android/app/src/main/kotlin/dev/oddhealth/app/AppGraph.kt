package dev.oddhealth.app

import android.app.Application
import androidx.work.Configuration
import dev.oddhealth.app.data.HealthDatabase
import dev.oddhealth.app.data.HealthRepository
import dev.oddhealth.app.debug.DebugLog
import dev.oddhealth.app.ring.RingChecks
import dev.oddhealth.app.ring.RingState
import dev.oddhealth.app.sync.SyncManager
import dev.oddhealth.app.update.UpdateManager
import dev.oddhealth.app.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide singletons, created once in [OddHealthApp]. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val database = HealthDatabase.create(app)
    val repository = HealthRepository(database.records())
    val debugLog = DebugLog(app) { settings.debugLogging.value }
    val ring = RingState(settings.firmware)
    val checks = RingChecks(app)
    val sync = SyncManager(app, repository, settings, debugLog, scope)
    val updates = UpdateManager(app, settings, debugLog)
}

/** Also configures WorkManager on demand (its start-up initializer is removed in the manifest). */
class OddHealthApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        UpdateWorker.schedule(this, graph.settings.updateNotifications.value)
        graph.scope.launch {
            graph.repository.backfillRestingHeartRate()
            // Catch up on anything unsynced, including kinds whose mapping changed in an update.
            graph.sync.syncSoon(5_000)
        }
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as OddHealthApp).graph
