package dev.oddhealth.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.oddhealth.app.data.HealthDatabase
import dev.oddhealth.app.data.HealthRepository
import dev.oddhealth.app.ui.model.DayData
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.model.RingStatus
import dev.oddhealth.app.ui.model.SyncMark
import dev.oddhealth.app.ui.model.SyncStatus
import dev.oddhealth.app.ui.model.HealthConnectState
import dev.oddhealth.app.ui.model.UiMapping
import dev.oddhealth.app.ui.model.Words
import dev.oddhealth.core.ingest.EvenExport
import dev.oddhealth.core.model.DataSource
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.model.SleepStage
import dev.oddhealth.core.model.SleepStageType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repo = HealthRepository(db.records())
    private val cst = ZoneOffset.ofHours(-6)

    private fun hr(hour: Long, avg: Int, version: Long) =
        HourlyVital(RecordKind.HEART_RATE_HOUR, Instant.parse("2026-11-17T06:00:00Z").plusSeconds(hour * 3600), cst, avg - 5, avg, avg + 5, DataSource.RING, version)

    @Test
    fun newerVersionsReplaceOlderOnesAndSyncTracksVersions() = runBlocking {
        assertEquals(2, repo.save(listOf(hr(0, 70, 1), hr(1, 72, 1))))
        assertEquals(0, repo.save(listOf(hr(0, 99, 0))))        // older: ignored
        assertEquals(1, repo.save(listOf(hr(1, 80, 2))))        // newer: replaces
        assertEquals(0, repo.save(listOf(hr(1, 80, 5))))        // newer but identical: no change
        val pending = repo.pending("hc", setOf(RecordKind.HEART_RATE_HOUR), 10)
        assertEquals(listOf(70, 80), pending.map { (it as HourlyVital).avg })
        repo.markSynced("hc", pending)
        assertEquals(0, repo.pending("hc", setOf(RecordKind.HEART_RATE_HOUR), 10).size)
        repo.save(listOf(hr(1, 81, 6)))                          // hour still filling up
        assertEquals(1, repo.pendingCount("hc", setOf(RecordKind.HEART_RATE_HOUR)).first())
    }

    @Test
    fun sleepRoundTripsAndMapsToTheDayItEnds() = runBlocking {
        val start = Instant.parse("2026-11-17T07:02:30Z")
        val stages = listOf(
            SleepStage(SleepStageType.AWAKE, start, start.plusSeconds(990)),
            SleepStage(SleepStageType.DEEP, start.plusSeconds(990), start.plusSeconds(6150)),
        )
        val night = SleepSession(start, start.plusSeconds(6150), cst, stages, 53, 93, DataSource.RING, 1)
        repo.save(listOf(night, SkinTemperature(start, night.end, cst, 35.6, DataSource.RING, 1)))
        val date = LocalDate.of(2026, 11, 17)
        val zone = ZoneId.of("America/Chicago")
        val from = date.atStartOfDay(zone).toInstant().minusSeconds(18 * 3600)
        val records = repo.range(from, date.plusDays(1).atStartOfDay(zone).toInstant()).first()
        assertEquals(night, records.filterIsInstance<SleepSession>().single())
        val day = DayData(date, zone, records, mapOf(night.key to 1L), setOf(RecordKind.SLEEP_SESSION), emptyList())
        val sleep = UiMapping.sleep(day)!!
        assertEquals(53, sleep.score)
        assertEquals("01:02", sleep.bedtime.toString())
        val detail = UiMapping.detail(Metric.SLEEP, day, FakeWords)
        assertEquals(SyncMark.SYNCED, detail.rows.first().mark)
        assertEquals(SyncMark.APP_ONLY, detail.rows.last().mark)
    }

    @Test
    fun restingHeartRateFollowsSleepAndHeartRate() = runBlocking {
        val start = Instant.parse("2026-11-17T07:00:00Z")
        val night = SleepSession(start, start.plusSeconds(5 * 3600), cst, emptyList(), null, null, DataSource.RING, 1)
        repo.save(listOf(night))
        repo.save((0L..4L).map { HourlyVital(RecordKind.HEART_RATE_HOUR, start.plusSeconds(it * 3600), cst, 50, 60 - it.toInt(), 70, DataSource.RING, 2) })
        val rhr = repo.byKind(RecordKind.RESTING_HEART_RATE).first().single() as dev.oddhealth.core.model.RestingHeartRate
        assertEquals(56, rhr.bpm)
        // A later, lower hour updates the estimate.
        repo.save(listOf(HourlyVital(RecordKind.HEART_RATE_HOUR, start.plusSeconds(2 * 3600), cst, 45, 52, 60, DataSource.RING, 3)))
        assertEquals(52, (repo.byKind(RecordKind.RESTING_HEART_RATE).first().single() as dev.oddhealth.core.model.RestingHeartRate).bpm)
        assertEquals(0, repo.backfillRestingHeartRate()) // already has one
    }

    /** Local check against a real export: ODD_EXPORT=/path/to.zip; prints a summary only. */
    @Test
    fun realExport() = runBlocking {
        val path = System.getenv("ODD_EXPORT") ?: return@runBlocking
        val result = EvenExport.read(File(path).inputStream(), 1)
        repo.save(result.records)
        val zone = ZoneId.of("America/Chicago")
        for (date in listOf(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 17))) {
            val from = date.atStartOfDay(zone).toInstant().minusSeconds(18 * 3600)
            val records = repo.range(from, date.plusDays(1).atStartOfDay(zone).toInstant()).first()
            val skins = repo.byKind(RecordKind.SKIN_TEMPERATURE).first().filterIsInstance<SkinTemperature>()
            val day = DayData(date, zone, records, emptyMap(), emptySet(), skins)
            val t = UiMapping.today(day, RingStatus("r", false, null, false, null, null, null), SyncStatus(HealthConnectState.READY, null, 0))
            println("DAY $date sleep=${t.sleep?.let { "${it.bedtime}-${it.wake} ${it.asleep}" }} hr=${t.heartRate?.let { "${it.min}-${it.max} avg ${it.avg}" }} " +
                "rhr=${t.heartRate?.resting} steps=${t.activity?.steps} kcal=${t.activity?.totalKcal} skin=${t.skin}")
        }
    }

    private object FakeWords : Words {
        override fun noData() = "none"
        override fun liveReading() = "live"
        override fun fromRing() = "ring"
        override fun hrCaption(avg: Int, latest: Int?, at: String?) = ""
        override fun rangeCaption(min: Int, max: Int, unit: String) = ""
        override fun activityCaption(kcal: Int, active: Int) = ""
        override fun sleepCaption(from: String, to: String, score: Int?, efficiency: Int?) = ""
        override fun skinCaption(delta: Double?) = ""
        override fun hrNote() = ""
        override fun sleepNote() = ""
        override fun skinNote() = ""
        override fun stage(type: SleepStageType) = type.name
        override fun score() = "score"
        override fun efficiency() = "eff"
        override fun steps(n: Int) = "$n"
        override fun stepsUnit() = "steps"
        override fun restingLabel() = "resting"
        override fun restingDetail() = "est"
        override fun kcal(n: Int, active: Int) = "$n"
    }
}
