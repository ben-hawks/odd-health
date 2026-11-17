package dev.oddhealth.core.sync

import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.RecordKind

/**
 * A place records are copied to: Health Connect today, possibly other services
 * later. A target only needs to say what it can store and how to write it; the
 * [SyncEngine] works out what is new.
 */
interface SyncTarget {
    /** Stable id, used to remember what has been synced to this target. */
    val id: String
    val displayName: String

    /** Kinds this target can store right now (may depend on permissions). */
    suspend fun writableKinds(): Set<RecordKind>

    /**
     * Write or replace [records]. Writing a record whose key was written before
     * must replace it, not duplicate it. [context] gives access to other
     * records when a mapping needs them (skin temperature baseline, for one).
     */
    suspend fun write(records: List<HealthRecord>, context: SyncContext): WriteResult
}

/**
 * [written]: stored. [skipped]: can never be stored by this target (e.g. a value
 * out of its allowed range); treated as done so they stop being retried.
 * [failed]: not stored this time; retried on the next sync.
 */
data class WriteResult(
    val written: List<HealthRecord>,
    val skipped: Map<HealthRecord, String> = emptyMap(),
    val failed: Map<HealthRecord, String> = emptyMap(),
)

/** Read access for targets that need more than the record itself. */
interface SyncContext {
    suspend fun history(kind: RecordKind, limit: Int): List<HealthRecord>
}

/** What has been sent where. */
interface SyncLedger {
    /** Records of [kinds] that [targetId] has not seen at their current version. */
    suspend fun pending(targetId: String, kinds: Set<RecordKind>, limit: Int): List<HealthRecord>

    suspend fun markSynced(targetId: String, records: List<HealthRecord>)
}

sealed interface SyncOutcome {
    data class Done(val target: String, val written: Int, val skipped: Int, val failed: Int) : SyncOutcome
    data class Skipped(val target: String, val reason: String) : SyncOutcome
}

class SyncEngine(
    private val ledger: SyncLedger,
    private val context: SyncContext,
    private val batchSize: Int = 500,
) {
    suspend fun sync(target: SyncTarget): SyncOutcome {
        val kinds = target.writableKinds()
        if (kinds.isEmpty()) return SyncOutcome.Skipped(target.displayName, "no data types allowed")
        var written = 0
        var skipped = 0
        var failed = 0
        val retryLater = HashSet<String>()
        while (true) {
            // Records that failed this run stay pending; skip past them rather than loop.
            val batch = ledger.pending(target.id, kinds, batchSize + retryLater.size).filter { it.key !in retryLater }
            if (batch.isEmpty()) break
            val result = target.write(batch.take(batchSize), context)
            ledger.markSynced(target.id, result.written + result.skipped.keys)
            written += result.written.size
            skipped += result.skipped.size
            failed += result.failed.size
            result.failed.keys.mapTo(retryLater) { it.key }
            if (result.written.isEmpty() && result.skipped.isEmpty() && result.failed.isEmpty()) break
        }
        return SyncOutcome.Done(target.displayName, written, skipped, failed)
    }
}
