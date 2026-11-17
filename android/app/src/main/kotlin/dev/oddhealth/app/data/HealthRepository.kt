package dev.oddhealth.app.data

import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.RestingHeartRateEstimate
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.sync.SyncContext
import dev.oddhealth.core.sync.SyncLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Stored records, plus the sync bookkeeping the [dev.oddhealth.core.sync.SyncEngine] needs. */
class HealthRepository(private val dao: RecordDao) : SyncLedger, SyncContext {

    /**
     * Store records; older versions of the same key are ignored. Returns how
     * many changed. Also updates the resting heart rate of every night whose
     * sleep or heart rate changed.
     */
    suspend fun save(records: List<HealthRecord>): Int {
        val changed = dao.upsertNewer(records.map(RecordCodec::toRow))
        if (changed > 0) updateRestingHeartRate(records)
        return changed
    }

    /** Estimate resting heart rate for every stored night that has none (data saved before the estimate existed). */
    suspend fun backfillRestingHeartRate(): Int {
        val have = dao.byKindOnce(RecordKind.RESTING_HEART_RATE.id).mapTo(HashSet()) { it.start }
        val estimates = dao.byKindOnce(RecordKind.SLEEP_SESSION.id).map(RecordCodec::fromRow).filterIsInstance<SleepSession>()
            .filter { it.end.epochSecond !in have }
            .mapNotNull { night ->
                val hours = dao.overlapping(RecordKind.HEART_RATE_HOUR.id, night.start.epochSecond, night.end.epochSecond)
                    .map(RecordCodec::fromRow).filterIsInstance<HourlyVital>()
                RestingHeartRateEstimate.forNight(night, hours)
            }
        return if (estimates.isEmpty()) 0 else dao.upsertNewer(estimates.map(RecordCodec::toRow))
    }

    private suspend fun updateRestingHeartRate(saved: List<HealthRecord>) {
        val relevant = saved.filter { it.kind == RecordKind.SLEEP_SESSION || it.kind == RecordKind.HEART_RATE_HOUR }
        if (relevant.isEmpty()) return
        // Nights touched by any saved sleep or heart-rate hour, found per day to keep queries small.
        val nights = HashMap<String, SleepSession>()
        for (group in relevant.groupBy { it.start.epochSecond / 86_400 }.values) {
            val from = group.minOf { it.start }.minusSeconds(86_400).epochSecond
            val to = group.maxOf { it.end }.plusSeconds(86_400).epochSecond
            dao.overlapping(RecordKind.SLEEP_SESSION.id, from, to).map(RecordCodec::fromRow)
                .filterIsInstance<SleepSession>()
                .filter { n -> group.any { it.start < n.end && it.end > n.start } }
                .forEach { nights[it.key] = it }
        }
        val estimates = nights.values.mapNotNull { night ->
            val hours = dao.overlapping(RecordKind.HEART_RATE_HOUR.id, night.start.epochSecond, night.end.epochSecond)
                .map(RecordCodec::fromRow).filterIsInstance<HourlyVital>()
            RestingHeartRateEstimate.forNight(night, hours)
        }
        if (estimates.isNotEmpty()) dao.upsertNewer(estimates.map(RecordCodec::toRow))
    }

    fun range(from: Instant, to: Instant): Flow<List<HealthRecord>> =
        dao.range(from.epochSecond, to.epochSecond).map { rows -> rows.map(RecordCodec::fromRow) }

    fun byKind(kind: RecordKind): Flow<List<HealthRecord>> = dao.byKind(kind.id).map { rows -> rows.map(RecordCodec::fromRow) }

    /**
     * Synced versions, by key, for records starting in the range. [scopes] gives
     * the ledger scope each kind is tracked under.
     */
    fun syncedIn(scopes: Map<RecordKind, String>, from: Instant, to: Instant): Flow<Map<String, Long>> =
        dao.syncedIn(scopes.values.distinct(), from.epochSecond, to.epochSecond).map { rows ->
            rows.filter { scopes[RecordKind.fromId(it.key.substringBefore(':'))] == it.target }.associate { it.key to it.version }
        }

    fun pendingCount(target: String, kinds: Set<RecordKind>): Flow<Int> =
        dao.pendingCount(target, kinds.map { it.id })

    fun counts(): Flow<Map<RecordKind, Int>> =
        dao.counts().map { list -> list.associate { RecordKind.fromId(it.kind) to it.n } }

    fun oldest(): Flow<Instant?> = dao.oldest().map { it?.let(Instant::ofEpochSecond) }

    override suspend fun pending(targetId: String, kinds: Set<RecordKind>, limit: Int): List<HealthRecord> =
        dao.pending(targetId, kinds.map { it.id }, limit).map(RecordCodec::fromRow)

    override suspend fun markSynced(targetId: String, records: List<HealthRecord>) {
        if (records.isNotEmpty()) dao.markSynced(records.map { SyncedRow(targetId, it.key, it.version) })
    }

    override suspend fun history(kind: RecordKind, limit: Int): List<HealthRecord> =
        dao.latest(kind.id, limit).map(RecordCodec::fromRow)
}
