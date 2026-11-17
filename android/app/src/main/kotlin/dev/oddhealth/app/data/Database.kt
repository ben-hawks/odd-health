package dev.oddhealth.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One stored record; columns are reused per kind, see [RecordCodec]. */
@Entity(tableName = "records", indices = [Index("kind", "start"), Index("start")])
data class RecordRow(
    @PrimaryKey val key: String,
    val kind: String,
    val start: Long,
    val end: Long,
    val zoneSeconds: Int,
    val source: String,
    val version: Long,
    val a: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
    val real: Double? = null,
    val text: String? = null,
)

/** The version of a record last sent to a sync target. */
@Entity(tableName = "synced", primaryKeys = ["target", "key"])
data class SyncedRow(val target: String, val key: String, val version: Long)

data class KindCount(val kind: String, val n: Int)

data class KeyVersion(val key: String, val version: Long)

@Dao
interface RecordDao {
    @Query("SELECT key, version FROM records WHERE key IN (:keys)")
    suspend fun versions(keys: List<String>): List<KeyVersion>

    @Upsert
    suspend fun upsert(rows: List<RecordRow>)

    /** Store rows that are new or newer than what is stored; returns how many were. */
    @Transaction
    suspend fun upsertNewer(rows: List<RecordRow>): Int {
        var changed = 0
        for (chunk in rows.chunked(500)) {
            val have = versions(chunk.map { it.key }).associate { it.key to it.version }
            val newer = chunk.filter { (have[it.key] ?: Long.MIN_VALUE) < it.version }
            if (newer.isNotEmpty()) upsert(newer)
            changed += newer.size
        }
        return changed
    }

    @Query("SELECT * FROM records WHERE start >= :from AND start < :to ORDER BY start")
    fun range(from: Long, to: Long): Flow<List<RecordRow>>

    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY start")
    fun byKind(kind: String): Flow<List<RecordRow>>

    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY start DESC LIMIT :limit")
    suspend fun latest(kind: String, limit: Int): List<RecordRow>

    @Query(
        """SELECT r.* FROM records r LEFT JOIN synced s ON s.key = r.key AND s.target = :target
           WHERE r.kind IN (:kinds) AND (s.version IS NULL OR s.version < r.version)
           ORDER BY r.start LIMIT :limit""",
    )
    suspend fun pending(target: String, kinds: List<String>, limit: Int): List<RecordRow>

    @Query(
        """SELECT COUNT(*) FROM records r LEFT JOIN synced s ON s.key = r.key AND s.target = :target
           WHERE r.kind IN (:kinds) AND (s.version IS NULL OR s.version < r.version)""",
    )
    fun pendingCount(target: String, kinds: List<String>): Flow<Int>

    @Upsert
    suspend fun markSynced(rows: List<SyncedRow>)

    @Query(
        """SELECT s.* FROM synced s JOIN records r ON r.key = s.key
           WHERE s.target = :target AND r.start >= :from AND r.start < :to""",
    )
    fun syncedIn(target: String, from: Long, to: Long): Flow<List<SyncedRow>>

    @Query("SELECT kind, COUNT(*) AS n FROM records GROUP BY kind")
    fun counts(): Flow<List<KindCount>>

    @Query("SELECT MIN(start) FROM records")
    fun oldest(): Flow<Long?>
}

@Database(entities = [RecordRow::class, SyncedRow::class], version = 1)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun records(): RecordDao

    companion object {
        fun create(context: Context): HealthDatabase =
            Room.databaseBuilder(context, HealthDatabase::class.java, "health.db").build()
    }
}
