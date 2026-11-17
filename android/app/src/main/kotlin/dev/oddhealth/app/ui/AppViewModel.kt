package dev.oddhealth.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.oddhealth.app.R
import dev.oddhealth.app.graph
import dev.oddhealth.app.ring.RingService
import dev.oddhealth.app.ui.model.DayData
import dev.oddhealth.app.ui.model.DebugState
import dev.oddhealth.app.ui.model.HealthConnectState
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.model.MetricDetailState
import dev.oddhealth.app.ui.model.RingScreenState
import dev.oddhealth.app.ui.model.SyncScreenState
import dev.oddhealth.app.ui.model.SyncStatus
import dev.oddhealth.app.ui.model.SyncTypeRow
import dev.oddhealth.app.ui.model.TodayState
import dev.oddhealth.app.ui.model.TypeStatus
import dev.oddhealth.app.ui.model.UiMapping
import dev.oddhealth.app.ui.model.Words
import dev.oddhealth.core.ingest.EvenExport
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepStageType
import dev.oddhealth.core.sync.SyncOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val repo = graph.repository

    /** Ticks every minute so "x min ago" and the current day stay current. */
    val now: StateFlow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Instant.now())

    private val messages = Channel<String>(Channel.BUFFERED)
    val message: Flow<String> = messages.receiveAsFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    private val words = object : Words {
        fun s(id: Int, vararg a: Any) = app.getString(id, *a)
        override fun noData() = s(R.string.no_data_day)
        override fun liveReading() = s(R.string.live_reading)
        override fun fromRing() = s(R.string.from_ring)
        override fun hrCaption(avg: Int, latest: Int?, at: String?) =
            if (latest != null && at != null) s(R.string.hr_caption, avg, latest, at) else s(R.string.hr_caption_avg, avg)
        override fun rangeCaption(min: Int, max: Int, unit: String) = s(R.string.range_caption, min, max, unit)
        override fun activityCaption(kcal: Int, active: Int) = s(R.string.kcal_line, kcal, active)
        override fun sleepCaption(from: String, to: String, score: Int?, efficiency: Int?) =
            listOfNotNull("$from – $to", score?.let { s(R.string.score_n, it) }, efficiency?.let { s(R.string.efficiency_n, it) })
                .joinToString(" · ")
        override fun skinCaption(delta: Double?) =
            delta?.let { s(R.string.vs_baseline, "%+.1f".format(it)) } ?: s(R.string.baseline_pending)
        override fun hrNote() = s(R.string.note_hr)
        override fun sleepNote() = s(R.string.note_sleep)
        override fun skinNote() = s(R.string.note_skin)
        override fun stage(type: SleepStageType) = s(
            when (type) {
                SleepStageType.DEEP -> R.string.stage_deep
                SleepStageType.LIGHT -> R.string.stage_light
                SleepStageType.REM -> R.string.stage_rem
                SleepStageType.AWAKE -> R.string.stage_awake
            },
        )
        override fun score() = s(R.string.sleep_score)
        override fun efficiency() = s(R.string.efficiency)
        override fun steps(n: Int) = s(R.string.n_steps, n)
        override fun stepsUnit() = s(R.string.unit_steps)
        override fun restingLabel() = s(R.string.resting_hr)
        override fun restingDetail() = s(R.string.resting_hr_detail)
        override fun kcal(n: Int, active: Int) = s(R.string.kcal_line, n, active)
    }

    private val syncStatus: Flow<SyncStatus> = combine(
        graph.sync.state, graph.settings.lastSync,
        graph.sync.scopes.flatMapLatest { scopes ->
            val counts = scopes.entries.groupBy({ it.value }, { it.key }).map { (scope, kinds) -> repo.pendingCount(scope, kinds.toSet()) }
            if (counts.isEmpty()) flowOf(0) else combine(counts) { it.sum() }
        },
    ) { state, last, pending -> SyncStatus(state, last, pending) }

    private fun day(date: LocalDate): Flow<DayData> {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().minus(Duration.ofHours(18))
        val to = date.plusDays(1).atStartOfDay(zone).toInstant()
        return combine(
            repo.range(from, to), graph.sync.scopes.flatMapLatest { repo.syncedIn(it, from, to) }, graph.sync.writable,
            repo.byKind(RecordKind.SKIN_TEMPERATURE),
        ) { records, synced, writable, skins ->
            DayData(date, zone, records, synced, writable, skins.filterIsInstance<SkinTemperature>())
        }
    }

    val today: StateFlow<TodayState?> = now.map { LocalDate.now() }.distinctUntilChanged()
        .flatMapLatest { date -> combine(day(date), graph.ring.status, syncStatus) { d, ring, sync -> UiMapping.today(d, ring, sync) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun detail(metric: Metric, date: LocalDate): Flow<MetricDetailState> = day(date).map { UiMapping.detail(metric, it, words) }

    val ring: StateFlow<RingScreenState?> = combine(
        graph.ring.status, graph.ring.events, graph.settings.stayConnected, graph.settings.debugLogging, graph.settings.hrPollMinutes,
    ) { status, events, stay, debug, poll -> RingScreenState(status, stay, events, debug, poll) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sync: StateFlow<SyncScreenState?> = combine(
        combine(syncStatus, graph.sync.skinSupported) { a, b -> a to b }, graph.sync.writable, repo.counts(), repo.oldest(), graph.sync.syncing,
    ) { (status, skinSupported), writable, counts, oldest, syncing ->
        fun st(kind: RecordKind) = if (kind in writable) TypeStatus.WRITTEN else TypeStatus.NEEDS_PERMISSION
        val s = { id: Int -> app.getString(id) }
        SyncScreenState(
            sync = status,
            types = listOf(
                SyncTypeRow(s(R.string.heart_rate), s(R.string.hc_heart_rate), st(RecordKind.HEART_RATE_HOUR), s(R.string.t_hr)),
                SyncTypeRow(s(R.string.resting_hr), s(R.string.resting_hr), st(RecordKind.RESTING_HEART_RATE), s(R.string.t_rhr)),
                SyncTypeRow(s(R.string.t_hr_range), null, TypeStatus.APP_ONLY, s(R.string.t_hr_range_note)),
                SyncTypeRow(s(R.string.hrv), s(R.string.hc_hrv), st(RecordKind.HRV_HOUR), s(R.string.t_hourly_avg_rmssd)),
                SyncTypeRow(s(R.string.spo2), s(R.string.hc_spo2), st(RecordKind.SPO2_HOUR), s(R.string.t_hourly_avg)),
                SyncTypeRow(s(R.string.t_steps), s(R.string.hc_steps), st(RecordKind.ACTIVITY_BUCKET), s(R.string.t_10min)),
                SyncTypeRow(s(R.string.t_calories), s(R.string.hc_calories), st(RecordKind.ACTIVITY_BUCKET), s(R.string.t_10min)),
                SyncTypeRow(s(R.string.sleep), s(R.string.hc_sleep), st(RecordKind.SLEEP_SESSION), null),
                SyncTypeRow(s(R.string.t_sleep_score), null, TypeStatus.APP_ONLY, s(R.string.t_sleep_score_note)),
                SyncTypeRow(s(R.string.t_body_temp), s(R.string.hc_body_temp), st(RecordKind.SKIN_TEMPERATURE), s(R.string.t_body_temp_note)),
                if (skinSupported || graph.sync.state.value != HealthConnectState.READY) {
                    SyncTypeRow(s(R.string.skin_temperature), s(R.string.skin_temperature), st(RecordKind.SKIN_TEMPERATURE), s(R.string.t_skin))
                } else {
                    SyncTypeRow(s(R.string.skin_temperature), null, TypeStatus.APP_ONLY, s(R.string.t_skin_unsupported))
                },
            ),
            stored = counts.entries.groupBy({ metricOf(it.key) }, { it.value }).mapValues { it.value.sum() },
            oldest = oldest?.atZone(ZoneId.systemDefault())?.toLocalDate(),
            syncing = syncing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val debugFiles = MutableStateFlow(graph.debugLog.files())

    val debug: StateFlow<DebugState?> = combine(graph.settings.debugLogging, debugFiles, now) { on, files, _ ->
        DebugState(on, graph.debugLog.files().size, graph.debugLog.files().sumOf { it.length() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun metricOf(kind: RecordKind) = when (kind) {
        RecordKind.HEART_RATE_HOUR, RecordKind.HEART_RATE_READING, RecordKind.RESTING_HEART_RATE -> Metric.HEART_RATE
        RecordKind.HRV_HOUR -> Metric.HRV
        RecordKind.SPO2_HOUR -> Metric.SPO2
        RecordKind.ACTIVITY_BUCKET -> Metric.ACTIVITY
        RecordKind.SLEEP_SESSION -> Metric.SLEEP
        RecordKind.SKIN_TEMPERATURE -> Metric.SKIN_TEMPERATURE
    }

    // --- actions ---------------------------------------------------------------

    fun refreshHealthConnect() = viewModelScope.launch { graph.sync.refresh() }

    fun syncNow() = viewModelScope.launch {
        val outcomes = graph.sync.syncNow()
        val written = outcomes.filterIsInstance<SyncOutcome.Done>().sumOf { it.written }
        val skipped = outcomes.filterIsInstance<SyncOutcome.Skipped>()
        messages.send(
            if (skipped.isNotEmpty()) getApplication<Application>().getString(R.string.sync_failed, skipped.first().reason)
            else getApplication<Application>().getString(R.string.sync_done, written),
        )
    }

    fun requestData() = RingService.requestData(getApplication())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Pull to refresh: ask the ring for data (if connected), give it a few
     * seconds to arrive, then sync to Health Connect.
     */
    fun refresh() = viewModelScope.launch {
        if (_refreshing.value) return@launch
        _refreshing.value = true
        try {
            if (graph.ring.status.value.connected) {
                val before = graph.ring.status.value.lastData
                RingService.requestData(getApplication())
                // Wait for the first data, then a moment for the rest of the batch.
                withTimeoutOrNull(10_000) { graph.ring.status.first { it.lastData != before } }
                delay(2_500)
            }
            if (graph.sync.state.value == HealthConnectState.READY) {
                val failed = graph.sync.syncNow().filterIsInstance<SyncOutcome.Skipped>()
                if (failed.isNotEmpty()) messages.send(getApplication<Application>().getString(R.string.sync_failed, failed.first().reason))
            }
        } finally {
            _refreshing.value = false
        }
    }

    fun setStayConnected(on: Boolean) {
        graph.settings.setStayConnected(on)
        if (on) RingService.start(getApplication()) else RingService.stop(getApplication())
    }

    fun setHrPollMinutes(minutes: Int) = graph.settings.setHrPollMinutes(minutes)

    fun setDebugLogging(on: Boolean) {
        graph.settings.setDebugLogging(on)
        if (!on) graph.debugLog.close()
        graph.debugLog.event("Debug logging turned on")
        debugFiles.value = graph.debugLog.files()
    }

    fun deleteDebugLogs() {
        graph.debugLog.deleteAll()
        debugFiles.value = graph.debugLog.files()
    }

    fun importExport(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        _importing.value = true
        try {
            val result = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)!!.use { EvenExport.read(it, System.currentTimeMillis()) }
            }
            val changed = graph.repository.save(result.records)
            graph.debugLog.event("Imported Even export: ${result.records.size} records, $changed new or updated, ${result.skippedRows} rows skipped")
            messages.send(app.getString(R.string.import_done, changed))
            if (changed > 0 && graph.sync.state.value == HealthConnectState.READY) graph.sync.syncSoon(1_000)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            graph.debugLog.event("Import failed: $e")
            messages.send(app.getString(R.string.import_failed, e.message ?: e.toString()))
        } finally {
            _importing.value = false
        }
    }
}
