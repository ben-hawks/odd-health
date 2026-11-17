package dev.oddhealth.core.ingest

import dev.oddhealth.core.model.ActivityBucket
import dev.oddhealth.core.model.DataSource
import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.HeartRateReading
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.model.SleepStage
import dev.oddhealth.core.model.SleepStageType
import dev.oddhealth.r1.Activity
import dev.oddhealth.r1.Decoded
import dev.oddhealth.r1.R1
import dev.oddhealth.r1.Sleep
import dev.oddhealth.r1.Vitals
import java.time.Instant
import java.time.ZoneOffset

/** Turns decoded ring payloads into stored records. Undated data is dropped. */
object RingIngest {
    fun records(decoded: Decoded, observedAtMillis: Long): List<HealthRecord> = when (decoded) {
        is Vitals -> vitals(decoded, observedAtMillis)
        is Activity -> activity(decoded, observedAtMillis)
        is Sleep -> sleep(decoded, observedAtMillis)
        else -> emptyList()
    }

    private fun zone(minutes: Int): ZoneOffset = ZoneOffset.ofTotalSeconds(minutes * 60)

    private fun vitals(v: Vitals, observed: Long): List<HealthRecord> {
        val kind = when (v.cmd) {
            R1.HEALTH_HEART_RATE -> RecordKind.HEART_RATE_HOUR
            R1.HEALTH_HRV -> RecordKind.HRV_HOUR
            R1.HEALTH_SPO2 -> RecordKind.SPO2_HOUR
            else -> return emptyList()
        }
        val z = zone(v.tzOffsetMin)
        val out = ArrayList<HealthRecord>()
        for (r in v.records) {
            val ts = r.ts ?: continue
            if (r.avg == 0) continue
            out += HourlyVital(kind, Instant.ofEpochSecond(ts), z, r.min, r.avg, r.max, DataSource.RING, observed)
        }
        val currentTs = v.currentTs
        if (kind == RecordKind.HEART_RATE_HOUR && currentTs != null && v.current > 0) {
            out += HeartRateReading(Instant.ofEpochSecond(currentTs), z, v.current, DataSource.RING, observed)
        }
        return out
    }

    private fun activity(a: Activity, observed: Long): List<HealthRecord> {
        val z = zone(a.tzOffsetMin)
        return a.records.mapNotNull { r ->
            val ts = r.ts ?: return@mapNotNull null
            ActivityBucket(Instant.ofEpochSecond(ts), z, r.steps, r.kcalActive, r.kcalTotal, DataSource.RING, observed)
        }
    }

    private fun sleep(s: Sleep, observed: Long): List<HealthRecord> {
        val z = zone(s.tzOffsetMin)
        val start = Instant.ofEpochSecond(s.startTs)
        val stages = ArrayList<SleepStage>()
        var t = start
        for (run in s.stages) {
            val end = t.plusSeconds(run.halfMinutes * 30L)
            stages += SleepStage(stageType(run.type), t, end)
            t = end
        }
        val session = SleepSession(start, Instant.ofEpochSecond(s.endTs), z, stages, s.score, s.efficiency, DataSource.RING, observed)
        val temp = s.skinTempC ?: return listOf(session)
        return listOf(session, SkinTemperature(session.start, session.end, z, temp, DataSource.RING, observed))
    }

    fun stageType(id: Int): SleepStageType = when (id) {
        1 -> SleepStageType.REM
        2 -> SleepStageType.LIGHT
        3 -> SleepStageType.DEEP
        else -> SleepStageType.AWAKE
    }
}
