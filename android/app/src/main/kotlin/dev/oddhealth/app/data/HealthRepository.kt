package dev.oddhealth.app.data

import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.sync.SyncContext
import dev.oddhealth.core.sync.SyncLedger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Stored records, plus the sync bookkeeping the [dev.oddhealth.core.sync.SyncEngine] needs. */
class HealthRepository(private val dao: RecordDao) : SyncLedger, SyncContext {

    /** Store records; older versions of the same key are ignored. Returns how many changed. */
    suspend fun save(records: List<HealthRecord>): Int = dao.upsertNewer(records.map(RecordCodec::toRow))

    fun range(from: Instant, to: Instant): Flow<List<HealthRecord>> =
        dao.range(from.epochSecond, to.epochSecond).map { rows -> rows.map(RecordCodec::fromRow) }

    fun byKind(kind: RecordKind): Flow<List<HealthRecord>> = dao.byKind(kind.id).map { rows -> rows.map(RecordCodec::fromRow) }

    /** Versions synced to [target] for records starting in the range, by key. */
    fun syncedIn(target: String, from: Instant, to: Instant): Flow<Map<String, Long>> =
        dao.syncedIn(target, from.epochSecond, to.epochSecond).map { rows -> rows.associate { it.key to it.version } }

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
