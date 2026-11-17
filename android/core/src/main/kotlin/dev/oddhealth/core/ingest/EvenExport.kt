package dev.oddhealth.core.ingest

import dev.oddhealth.core.model.ActivityBucket
import dev.oddhealth.core.model.DataSource
import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.model.SleepStage
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream

/**
 * Reads the Even app's health export (Even_health_data_YYYYMMDD.zip).
 *
 * The export holds several rows per time slot (the app's merged history plus
 * raw ring pushes). For each slot we keep the row from the app's merged
 * history ("R1") when there is one, as it is the final value.
 */
object EvenExport {
    class Result(val records: List<HealthRecord>, val skippedRows: Int)

    fun read(zip: InputStream, importedAtMillis: Long): Result {
        val files = HashMap<String, List<Map<String, String>>>()
        ZipInputStream(zip).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (name.endsWith(".csv")) files[name] = Csv.parse(z.readBytes().toString(Charsets.UTF_8))
            }
        }
        require(files.isNotEmpty()) { "No CSV files in this zip. Is it an Even health export?" }
        var skipped = 0
        val out = LinkedHashMap<String, HealthRecord>()
        fun put(r: HealthRecord, preferred: Boolean) {
            if (preferred || r.key !in out) out[r.key] = r
        }

        fun vitals(file: String, kind: RecordKind) {
            val bySlot = HashMap<Pair<OffsetDateTime, String>, MutableMap<String, Int>>()
            for (row in files[file].orEmpty()) {
                val t = time(row["timestamp"]) ?: run { skipped++; null } ?: continue
                val v = row["value"]?.toDoubleOrNull()?.toInt() ?: run { skipped++; null } ?: continue
                bySlot.getOrPut(t to (row["source"] ?: "")) { HashMap() }[row["ty"] ?: "avg"] = v
            }
            for ((slot, v) in bySlot) {
                val (t, source) = slot
                val avg = v["avg"] ?: continue
                if (avg == 0) continue
                put(
                    HourlyVital(kind, t.toInstant(), t.offset, v["min"] ?: avg, avg, v["max"] ?: avg, DataSource.EVEN_EXPORT, importedAtMillis),
                    source == "R1",
                )
            }
        }
        vitals("heart_rate.csv", RecordKind.HEART_RATE_HOUR)
        vitals("hrv.csv", RecordKind.HRV_HOUR)
        vitals("blood_oxygen.csv", RecordKind.SPO2_HOUR)

        val steps = HashMap<Pair<OffsetDateTime, String>, Int>()
        for (row in files["steps.csv"].orEmpty()) {
            val t = time(row["timestamp"]) ?: continue
            steps[t to (row["source"] ?: "")] = row["value"]?.toDoubleOrNull()?.toInt() ?: 0
        }
        for (row in files["calories.csv"].orEmpty()) {
            val t = time(row["timestamp"]) ?: run { skipped++; null } ?: continue
            val source = row["source"] ?: ""
            val total = row["value"]?.toDoubleOrNull()?.toInt() ?: continue
            val active = row["active"]?.toDoubleOrNull()?.toInt()
            // Rows without the resting/active split are partial live pushes; the merged row replaces them.
            put(
                ActivityBucket(t.toInstant(), t.offset, steps[t to source] ?: 0, active ?: 0, total, DataSource.EVEN_EXPORT, importedAtMillis),
                source == "R1" && active != null,
            )
        }

        val sleepRows = files["sleep.csv"].orEmpty()
        val temps = files["skin_temperature.csv"].orEmpty().associateBy({ it["timestamp"] }, { it["value"]?.toDoubleOrNull() })
        // A night appears many times as it grows; keep the longest row for each start time.
        val nights = sleepRows
            .filter { (it["total_time"]?.toIntOrNull() ?: 0) > 0 }
            .groupBy { it["start_ts"] }
            .mapNotNull { (_, rows) ->
                val longest = rows.maxByOrNull { time(it["end_ts"])?.toEpochSecond() ?: 0 } ?: return@mapNotNull null
                // Temperature rows share a timestamp with one of the night's rows; the latest is the final value.
                longest to rows.sortedByDescending { time(it["timestamp"])?.toEpochSecond() ?: 0 }
                    .firstNotNullOfOrNull { r -> temps[r["timestamp"]]?.takeIf { it > 0 } }
            }
        for ((row, temp) in nights) {
            val start = time(row["start_ts"]) ?: run { skipped++; null } ?: continue
            val end = time(row["end_ts"]) ?: continue
            val stages = ArrayList<SleepStage>()
            var t = start.toInstant()
            for (run in Json.stageRuns(row["stages"] ?: "[]")) {
                val e = t.plusSeconds(run.second * 30L)
                stages += SleepStage(RingIngest.stageType(run.first), t, e)
                t = e
            }
            val session = SleepSession(start.toInstant(), end.toInstant(), start.offset, stages, null, null, DataSource.EVEN_EXPORT, importedAtMillis)
            put(session, true)
            if (temp != null) {
                put(SkinTemperature(session.start, session.end, session.zone, temp, DataSource.EVEN_EXPORT, importedAtMillis), true)
            }
        }
        return Result(out.values.toList(), skipped)
    }

    private fun time(s: String?): OffsetDateTime? = try {
        s?.let(OffsetDateTime::parse)
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }
}

/** Minimal CSV reader: header row, comma separated, double-quoted fields with "" escapes. */
internal object Csv {
    fun parse(text: String): List<Map<String, String>> {
        val rows = ArrayList<List<String>>()
        var field = StringBuilder()
        var row = ArrayList<String>()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field = StringBuilder() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += field.toString(); field = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first()
        return rows.drop(1).map { r -> header.indices.associate { header[it] to r.getOrElse(it) { "" } } }
    }
}

/** Just enough JSON for `[{"type":0,"half_minutes":27},...]`. */
internal object Json {
    private val run = Regex("""\{\s*"type"\s*:\s*(\d+)\s*,\s*"half_minutes"\s*:\s*(\d+)\s*\}""")
    fun stageRuns(text: String): List<Pair<Int, Int>> =
        run.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
}
