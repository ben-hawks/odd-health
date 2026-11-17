package dev.oddhealth.core

import dev.oddhealth.core.ingest.EvenExport
import dev.oddhealth.core.ingest.RingIngest
import dev.oddhealth.core.model.ActivityBucket
import dev.oddhealth.core.model.DataSource
import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.HeartRateReading
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.SkinBaseline
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.model.SleepStageType
import dev.oddhealth.core.sync.SyncContext
import dev.oddhealth.core.sync.SyncEngine
import dev.oddhealth.core.sync.SyncLedger
import dev.oddhealth.core.sync.SyncOutcome
import dev.oddhealth.core.sync.SyncTarget
import dev.oddhealth.core.sync.WriteResult
import dev.oddhealth.r1.HourRecord
import dev.oddhealth.r1.R1
import dev.oddhealth.r1.Sleep
import dev.oddhealth.r1.SleepStage as WireStage
import dev.oddhealth.r1.Vitals
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CoreTest {
    private val cst = ZoneOffset.ofHours(-6)

    private fun zip(vararg files: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry("Even_health_data/$name"))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun exportPrefersMergedRowsAndPicksLongestNight() {
        val result = EvenExport.read(
            zip(
                "heart_rate.csv" to """
                    timestamp,value,source,ty
                    2026-11-16T22:00:00.000-06:00,101.0,ring1Notify,min
                    2026-11-16T22:00:00.000-06:00,120.0,ring1Notify,max
                    2026-11-16T22:00:00.000-06:00,99.0,ring1Notify,avg
                    2026-11-16T22:00:00.000-06:00,101,R1,min
                    2026-11-16T22:00:00.000-06:00,120,R1,max
                    2026-11-16T22:00:00.000-06:00,106,R1,avg
                """.trimIndent(),
                "steps.csv" to "timestamp,value,source\n2026-11-16T22:50:00.000-06:00,12,R1\n",
                "calories.csv" to "timestamp,value,resting,active,source\n2026-11-16T22:50:00.000-06:00,1.0,,,ring1Notify\n2026-11-16T22:50:00.000-06:00,18,17,1,R1\n",
                "sleep.csv" to """
                    timestamp,start_ts,end_ts,total_time,wake_time,rem_time,light_time,deep_time,stages,source,tz,body_temp_delta
                    2026-11-17T00:00:00.000-06:00,2026-11-17T01:02:30.000-06:00,2026-11-17T01:30:00.000-06:00,900,750,0,900,0,"[{""type"":0,""half_minutes"":25},{""type"":2,""half_minutes"":30}]",R1,America/Chicago,0
                    2026-11-17T00:00:00.000-06:00,2026-11-17T01:02:30.000-06:00,2026-11-17T02:00:00.000-06:00,2700,750,0,1800,900,"[{""type"":0,""half_minutes"":25},{""type"":2,""half_minutes"":60},{""type"":3,""half_minutes"":30}]",R1,America/Chicago,0
                """.trimIndent(),
                "skin_temperature.csv" to "timestamp,value,source\n2026-11-17T00:00:00.000-06:00,35.6,R1\n",
            ),
            importedAtMillis = 1,
        )
        val hr = result.records.filterIsInstance<HourlyVital>().single()
        assertEquals(Triple(101, 106, 120), Triple(hr.min, hr.avg, hr.max))
        val act = result.records.filterIsInstance<ActivityBucket>().single()
        assertEquals(Triple(12, 1, 18), Triple(act.steps, act.activeKcal, act.totalKcal))
        val night = result.records.filterIsInstance<SleepSession>().single()
        assertEquals(1800L, night.total(SleepStageType.LIGHT))
        assertEquals(900L, night.total(SleepStageType.DEEP))
        assertEquals(night.end, night.stages.last().end)
        assertEquals(35.6, result.records.filterIsInstance<SkinTemperature>().single().celsius)
    }

    @Test
    fun ringVitalsBecomeHoursAndALiveReading() {
        val base = 1_794_895_200L // local midnight at -06:00
        val v = Vitals(R1.HEALTH_HEART_RATE, -360, base, 91, base + 100, listOf(HourRecord(0, 80, 95, 70, base)))
        val records = RingIngest.records(v, observedAtMillis = 5)
        assertEquals(2, records.size)
        assertIs<HourlyVital>(records[0])
        assertEquals(Instant.ofEpochSecond(base + 100), (records[1] as HeartRateReading).start)
    }

    @Test
    fun ringSleepBecomesSessionAndSkinTemperature() {
        val s = Sleep(1000, 1000 + 90 * 30, -360, 93, 53, 35.6, 0, 0, 0, 0, 0,
            listOf(WireStage(0, 10), WireStage(2, 50), WireStage(3, 30)))
        val (session, temp) = RingIngest.records(s, 1)
        session as SleepSession
        assertEquals(53, session.score)
        assertEquals(80 * 30L, session.asleepSeconds)
        assertEquals(35.6, (temp as SkinTemperature).celsius)
    }

    @Test
    fun restingHeartRateIsLowestHourlyAverageDuringMainSleep() {
        val start = Instant.parse("2026-11-17T07:00:00Z")
        val night = SleepSession(start, start.plusSeconds(4 * 3600 + 1200), cst, emptyList(), null, null, DataSource.RING, 2)
        fun hour(h: Long, avg: Int) = HourlyVital(RecordKind.HEART_RATE_HOUR, start.plusSeconds(h * 3600), cst, avg - 3, avg, avg + 3, DataSource.RING, 1)
        val hours = listOf(hour(-1, 40), hour(0, 62), hour(1, 55), hour(2, 58), hour(4, 50), hour(5, 45))
        // hour -1 is before sleep, hour 4 is only 20 minutes inside it, hour 5 after it
        val rhr = dev.oddhealth.core.model.RestingHeartRateEstimate.forNight(night, hours)!!
        assertEquals(55, rhr.bpm)
        assertEquals(night.end, rhr.start)
        val nap = night.copy(end = start.plusSeconds(3600))
        assertNull(dev.oddhealth.core.model.RestingHeartRateEstimate.forNight(nap, hours))
    }

    @Test
    fun skinBaselineIsMedianOfEarlierNights() {
        fun night(day: Long, c: Double) =
            SkinTemperature(Instant.ofEpochSecond(day * 86400), Instant.ofEpochSecond(day * 86400 + 28800), cst, c, DataSource.RING, 1)
        val history = listOf(night(1, 35.0), night(2, 35.4), night(3, 35.2), night(4, 0.0))
        assertNull(SkinBaseline.forNight(night(3, 35.2), history))
        assertEquals(35.2, SkinBaseline.forNight(night(5, 36.0), history))
    }

    @Test
    fun syncEngineMovesPastRecordsThatFail() = runTest {
        val records = (0 until 5).map {
            HourlyVital(RecordKind.HEART_RATE_HOUR, Instant.ofEpochSecond(it * 3600L), cst, 60, 70, 80, DataSource.RING, 1)
        }
        val synced = HashSet<String>()
        val ledger = object : SyncLedger {
            override suspend fun pending(targetId: String, kinds: Set<RecordKind>, limit: Int) =
                records.filter { it.key !in synced }.take(limit)
            override suspend fun markSynced(targetId: String, records: List<HealthRecord>) {
                records.forEach { synced += it.key }
            }
        }
        val target = object : SyncTarget {
            override val id = "t"
            override val displayName = "T"
            override suspend fun writableKinds() = setOf(RecordKind.HEART_RATE_HOUR)
            override suspend fun write(records: List<HealthRecord>, context: SyncContext): WriteResult {
                val (bad, good) = records.partition { it.start.epochSecond == 0L }
                return WriteResult(good, failed = bad.associateWith { "no" })
            }
        }
        val context = object : SyncContext {
            override suspend fun history(kind: RecordKind, limit: Int) = emptyList<HealthRecord>()
        }
        assertEquals(SyncOutcome.Done("T", 4, 0, 1), SyncEngine(ledger, context, batchSize = 2).sync(target))
    }

    @Test
    fun syncEngineWritesPendingAndRemembers() = runTest {
        val records = (0 until 7).map {
            HourlyVital(RecordKind.HEART_RATE_HOUR, Instant.ofEpochSecond(it * 3600L), cst, 60, 70, 80, DataSource.RING, 1)
        }
        val ledger = object : SyncLedger {
            val synced = HashMap<String, Long>()
            override suspend fun pending(targetId: String, kinds: Set<RecordKind>, limit: Int) =
                records.filter { it.kind in kinds && (synced[it.key] ?: -1) < it.version }.take(limit)
            override suspend fun markSynced(targetId: String, records: List<HealthRecord>) {
                records.forEach { synced[it.key] = it.version }
            }
        }
        val target = object : SyncTarget {
            val got = ArrayList<HealthRecord>()
            override val id = "test"
            override val displayName = "Test"
            override suspend fun writableKinds() = setOf(RecordKind.HEART_RATE_HOUR)
            override suspend fun write(records: List<HealthRecord>, context: SyncContext): WriteResult {
                got += records
                return WriteResult(records)
            }
        }
        val context = object : SyncContext {
            override suspend fun history(kind: RecordKind, limit: Int) = emptyList<HealthRecord>()
        }
        val engine = SyncEngine(ledger, context, batchSize = 3)
        assertEquals(SyncOutcome.Done("Test", 7, 0, 0), engine.sync(target))
        assertEquals(SyncOutcome.Done("Test", 0, 0, 0), engine.sync(target))
        assertEquals(7, target.got.size)
    }
}
