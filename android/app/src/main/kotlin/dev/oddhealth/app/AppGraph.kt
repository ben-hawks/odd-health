package dev.oddhealth.app

import android.app.Application
import dev.oddhealth.app.data.HealthDatabase
import dev.oddhealth.app.data.HealthRepository
import dev.oddhealth.app.debug.DebugLog
import dev.oddhealth.app.ring.RingState
import dev.oddhealth.app.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons, created once in [OddHealthApp]. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(app)
    val database = HealthDatabase.create(app)
    val repository = HealthRepository(database.records())
    val debugLog = DebugLog(app) { settings.debugLogging.value }
    val ring = RingState(settings.firmware)
    val sync = SyncManager(app, repository, settings, debugLog, scope)
}

class OddHealthApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val android.content.Context.graph: AppGraph get() = (applicationContext as OddHealthApp).graph
