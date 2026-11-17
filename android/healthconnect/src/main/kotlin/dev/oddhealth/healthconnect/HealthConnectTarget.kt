package dev.oddhealth.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyTemperatureMeasurementLocation
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import dev.oddhealth.core.model.RestingHeartRate
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.units.kilocalories
import dev.oddhealth.core.model.ActivityBucket
import dev.oddhealth.core.model.HealthRecord
import dev.oddhealth.core.model.HeartRateReading
import dev.oddhealth.core.model.HourlyVital
import dev.oddhealth.core.model.RecordKind
import dev.oddhealth.core.model.SkinBaseline
import dev.oddhealth.core.model.SkinTemperature
import dev.oddhealth.core.model.SleepSession
import dev.oddhealth.core.model.SleepStageType
import dev.oddhealth.core.sync.SyncContext
import dev.oddhealth.core.sync.SyncTarget
import dev.oddhealth.core.sync.WriteResult
import kotlin.reflect.KClass

/**
 * Writes records to Health Connect. Each record gets a stable client record id
 * and its version, so writing the same hour again replaces it.
 */
class HealthConnectTarget(private val context: Context) : SyncTarget {
    override val id = "health-connect"
    override val displayName = "Health Connect"

    private val client: HealthConnectClient? by lazy {
        if (sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null
    }

    private val device = Device(type = Device.TYPE_RING, manufacturer = "Even Realities", model = "R1")

    /** Whether this phone's Health Connect has the skin temperature type; set by [writableKinds]. */
    var skinSupported = false
        private set

    /** Granted write permissions, as of the last [writableKinds] call. */
    private var granted: Set<String> = emptySet()

    private fun canWrite(type: KClass<out Record>) = HealthPermission.getWritePermission(type) in granted

    private fun canWriteSkin() = skinSupported && canWrite(SkinTemperatureRecord::class)

    override suspend fun writableKinds(): Set<RecordKind> {
        val c = client ?: return emptySet()
        granted = c.permissionController.getGrantedPermissions()
        skinSupported = skinTemperatureSupported(c)
        return HealthConnectMapping.kinds.filterTo(HashSet()) { kind ->
            if (kind == RecordKind.SKIN_TEMPERATURE) {
                // Nightly temperature goes out as body temperature and, where supported, skin temperature.
                canWrite(BodyTemperatureRecord::class) || canWriteSkin()
            } else {
                HealthConnectMapping.recordTypes(kind).all(::canWrite)
            }
        }
    }

    override suspend fun write(records: List<HealthRecord>, context: SyncContext): WriteResult {
        val c = client ?: return WriteResult(emptyList(), failed = records.associateWith { "Health Connect not available" })
        var history: List<SkinTemperature>? = null
        val skipped = HashMap<HealthRecord, String>()
        val failed = HashMap<HealthRecord, String>()
        val mapped = ArrayList<Pair<HealthRecord, List<Record>>>()
        val earlyBody = ArrayList<Record>()
        for (r in records) {
            try {
                if (r is SkinTemperature && history == null) {
                    history = context.history(RecordKind.SKIN_TEMPERATURE, SkinBaseline.NIGHTS * 8).filterIsInstance<SkinTemperature>()
                }
                mapped += r to map(r, history.orEmpty())
            } catch (e: BaselineNotReady) {
                // The body temperature half doesn't need a baseline.
                val body = if (canWrite(BodyTemperatureRecord::class)) listOf(bodyTemperature(r as SkinTemperature)) else emptyList()
                if (r.end < java.time.Instant.now().minus(java.time.Duration.ofDays(3))) {
                    // One of the first nights ever: it will never get a baseline. Write what we can and finish.
                    mapped += r to body
                } else {
                    // Recent: write body temperature now, retry skin temperature once there is a baseline.
                    if (body.isNotEmpty()) earlyBody += body
                    failed[r] = e.message!!
                }
            } catch (e: IllegalArgumentException) {
                // Health Connect rejects values outside its ranges when the record is built.
                skipped[r] = e.message ?: "invalid value"
            }
        }
        if (earlyBody.isNotEmpty()) {
            try {
                c.insertRecords(earlyBody)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        val written = ArrayList<HealthRecord>()
        val all = mapped.flatMap { it.second }
        try {
            if (all.isNotEmpty()) c.insertRecords(all)
            mapped.mapTo(written) { it.first }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // One bad record fails the whole batch; retry one by one to find it.
            for ((r, recs) in mapped) {
                try {
                    if (recs.isNotEmpty()) c.insertRecords(recs)
                    written += r
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Only a value Health Connect rejects is permanent; anything else (rate limits, IPC) is retried.
                    if (e is IllegalArgumentException) skipped[r] = e.toString() else failed[r] = e.toString()
                }
            }
        }
        return WriteResult(written, skipped, failed)
    }

    private class BaselineNotReady(message: String) : Exception(message)

    private fun metadata(r: HealthRecord, suffix: String = "") =
        Metadata.autoRecorded(device = device, clientRecordId = r.key + suffix, clientRecordVersion = r.version)

    /** Health Connect records for one of ours. May be empty. Throws IllegalArgumentException if unusable. */
    private fun map(r: HealthRecord, skinHistory: List<SkinTemperature>): List<Record> = when (r) {
        is HourlyVital -> {
            val mid = r.start.plusSeconds(1800)
            when (r.kind) {
                RecordKind.HEART_RATE_HOUR -> listOf(
                    HeartRateRecord(
                        startTime = r.start, startZoneOffset = r.zone, endTime = r.end, endZoneOffset = r.zone,
                        samples = listOf(HeartRateRecord.Sample(mid, r.avg.toLong())),
                        metadata = metadata(r),
                    ),
                )
                RecordKind.HRV_HOUR -> listOf(
                    HeartRateVariabilityRmssdRecord(
                        time = mid, zoneOffset = r.zone, heartRateVariabilityMillis = r.avg.toDouble(), metadata = metadata(r),
                    ),
                )
                RecordKind.SPO2_HOUR -> listOf(
                    OxygenSaturationRecord(time = mid, zoneOffset = r.zone, percentage = Percentage(r.avg.toDouble()), metadata = metadata(r)),
                )
                else -> emptyList()
            }
        }
        is HeartRateReading -> listOf(
            HeartRateRecord(
                startTime = r.start, startZoneOffset = r.zone, endTime = r.start.plusSeconds(1), endZoneOffset = r.zone,
                samples = listOf(HeartRateRecord.Sample(r.start, r.bpm.toLong())),
                metadata = metadata(r),
            ),
        )
        is ActivityBucket -> buildList {
            if (r.steps > 0) {
                add(StepsRecord(r.start, r.zone, r.end, r.zone, r.steps.toLong(), metadata(r, ":steps")))
            }
            if (r.totalKcal > 0) {
                add(TotalCaloriesBurnedRecord(r.start, r.zone, r.end, r.zone, r.totalKcal.kilocalories, metadata(r, ":kcal")))
            }
            if (r.activeKcal > 0) {
                add(ActiveCaloriesBurnedRecord(r.start, r.zone, r.end, r.zone, r.activeKcal.kilocalories, metadata(r, ":active")))
            }
        }
        is SleepSession -> listOf(
            SleepSessionRecord(
                startTime = r.start, startZoneOffset = r.zone, endTime = r.end, endZoneOffset = r.zone,
                notes = r.score?.let { "Even R1 sleep score $it" + (r.efficiency?.let { e -> ", efficiency $e%" } ?: "") },
                stages = r.stages.map { SleepSessionRecord.Stage(it.start, it.end, stage(it.type)) },
                metadata = metadata(r),
            ),
        )
        is RestingHeartRate -> listOf(RestingHeartRateRecord(r.start, r.zone, r.bpm.toLong(), metadata(r)))
        is SkinTemperature -> {
            val body = if (canWrite(BodyTemperatureRecord::class)) listOf(bodyTemperature(r)) else emptyList()
            if (!canWriteSkin()) return body
            val baseline = SkinBaseline.forNight(r, skinHistory)
                ?: throw BaselineNotReady("waiting for ${SkinBaseline.MIN_NIGHTS} nights to set a baseline")
            body + listOf(
                SkinTemperatureRecord(
                    startTime = r.start, startZoneOffset = r.zone, endTime = r.end, endZoneOffset = r.zone,
                    deltas = listOf(SkinTemperatureRecord.Delta(r.start, TemperatureDelta.celsius(r.celsius - baseline))),
                    baseline = Temperature.celsius(baseline),
                    measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER,
                    metadata = metadata(r),
                ),
            )
        }
    }

    /** The nightly finger temperature, stamped at wake-up, measured at the finger. */
    private fun bodyTemperature(r: SkinTemperature) = BodyTemperatureRecord(
        time = r.end,
        zoneOffset = r.zone,
        temperature = Temperature.celsius(r.celsius),
        measurementLocation = BodyTemperatureMeasurementLocation.MEASUREMENT_LOCATION_FINGER,
        metadata = metadata(r, ":body"),
    )

    private fun stage(type: SleepStageType): Int = when (type) {
        SleepStageType.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
        SleepStageType.REM -> SleepSessionRecord.STAGE_TYPE_REM
        SleepStageType.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        SleepStageType.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
    }

    companion object {
        fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

        fun skinTemperatureSupported(client: HealthConnectClient): Boolean =
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }
}

/** What goes where. Also drives the app's "what is synced" screen. */
object HealthConnectMapping {
    val kinds = listOf(
        RecordKind.HEART_RATE_HOUR, RecordKind.HEART_RATE_READING, RecordKind.HRV_HOUR, RecordKind.SPO2_HOUR,
        RecordKind.ACTIVITY_BUCKET, RecordKind.SLEEP_SESSION, RecordKind.SKIN_TEMPERATURE, RecordKind.RESTING_HEART_RATE,
    )

    fun recordTypes(kind: RecordKind): List<KClass<out Record>> = when (kind) {
        RecordKind.HEART_RATE_HOUR, RecordKind.HEART_RATE_READING -> listOf(HeartRateRecord::class)
        RecordKind.HRV_HOUR -> listOf(HeartRateVariabilityRmssdRecord::class)
        RecordKind.SPO2_HOUR -> listOf(OxygenSaturationRecord::class)
        RecordKind.ACTIVITY_BUCKET -> listOf(StepsRecord::class, TotalCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord::class)
        RecordKind.SLEEP_SESSION -> listOf(SleepSessionRecord::class)
        RecordKind.SKIN_TEMPERATURE -> listOf(SkinTemperatureRecord::class, BodyTemperatureRecord::class)
        RecordKind.RESTING_HEART_RATE -> listOf(RestingHeartRateRecord::class)
    }

    /** Every write permission the app can ask for. */
    val permissions: Set<String> =
        kinds.flatMap(::recordTypes).toSet().mapTo(HashSet()) { HealthPermission.getWritePermission(it) }
}
