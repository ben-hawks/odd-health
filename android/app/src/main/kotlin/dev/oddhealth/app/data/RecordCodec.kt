package dev.oddhealth.app.data

import dev.oddhealth.core.model.ActivityBucket
import dev.oddhealth.core.model.DataSource
import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.HeartRateReading
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.RestingHeartRate
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.model.SleepStage
import dev.oddhealth.core.model.SleepStageType
import java.time.Instant
import java.time.ZoneOffset

/**
 * Maps records to rows and back.
 *
 * | kind            | a       | b          | c         | real    | text                    |
 * |-----------------|---------|------------|-----------|---------|-------------------------|
 * | hourly vitals   | min     | avg        | max       |         |                         |
 * | live heart rate | bpm     |            |           |         |                         |
 * | activity        | steps   | active kcal| total kcal|         |                         |
 * | sleep           | score+1 | effic.+1   |           |         | stage:startSec:endSec;… |
 * | skin temp       |         |            |           | celsius |                         |
 * | resting HR      | bpm     | readings   |           |         |                         |
 *
 * Score and efficiency are stored plus one so 0 can mean "none".
 */
object RecordCodec {
    fun toRow(r: HealthRecord): RecordRow {
        val base = RecordRow(r.key, r.kind.id, r.start.epochSecond, r.end.epochSecond, r.zone.totalSeconds, r.source.name, r.version)
        return when (r) {
            is HourlyVital -> base.copy(a = r.min, b = r.avg, c = r.max)
            is HeartRateReading -> base.copy(a = r.bpm)
            is ActivityBucket -> base.copy(a = r.steps, b = r.activeKcal, c = r.totalKcal)
            is SleepSession -> base.copy(
                a = (r.score ?: -1) + 1,
                b = (r.efficiency ?: -1) + 1,
                text = r.stages.joinToString(";") { "${it.type.ordinal}:${it.start.epochSecond}:${it.end.epochSecond}" },
            )
            is SkinTemperature -> base.copy(real = r.celsius)
            is RestingHeartRate -> base.copy(a = r.bpm, b = r.readings)
        }
    }

    fun fromRow(row: RecordRow): HealthRecord {
        val start = Instant.ofEpochSecond(row.start)
        val end = Instant.ofEpochSecond(row.end)
        val zone = ZoneOffset.ofTotalSeconds(row.zoneSeconds)
        val source = DataSource.valueOf(row.source)
        return when (val kind = RecordKind.fromId(row.kind)) {
            RecordKind.HEART_RATE_HOUR, RecordKind.HRV_HOUR, RecordKind.SPO2_HOUR ->
                HourlyVital(kind, start, zone, row.a, row.b, row.c, source, row.version)
            RecordKind.HEART_RATE_READING -> HeartRateReading(start, zone, row.a, source, row.version)
            RecordKind.ACTIVITY_BUCKET -> ActivityBucket(start, zone, row.a, row.b, row.c, source, row.version)
            RecordKind.SLEEP_SESSION -> SleepSession(
                start, end, zone,
                stages = row.text.orEmpty().split(';').filter { it.isNotEmpty() }.map {
                    val (t, s, e) = it.split(':')
                    SleepStage(SleepStageType.entries[t.toInt()], Instant.ofEpochSecond(s.toLong()), Instant.ofEpochSecond(e.toLong()))
                },
                score = (row.a - 1).takeIf { it >= 0 },
                efficiency = (row.b - 1).takeIf { it >= 0 },
                source = source, version = row.version,
            )
            RecordKind.SKIN_TEMPERATURE -> SkinTemperature(start, end, zone, row.real ?: 0.0, source, row.version)
            RecordKind.RESTING_HEART_RATE -> RestingHeartRate(start, zone, row.a, source, row.version, readings = row.b)
        }
    }
}
