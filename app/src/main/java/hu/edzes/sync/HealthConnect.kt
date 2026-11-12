package hu.edzes.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

class Hc(ctx: Context) {
    private val client = HealthConnectClient.getOrCreate(ctx)

    suspend fun granted() = client.permissionController.getGrantedPermissions().containsAll(PERMS)

    suspend fun sessions(from: Instant): List<ExerciseSessionRecord> {
        val out = mutableListOf<ExerciseSessionRecord>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.after(from), pageToken = token))
            out += r.records
            token = r.pageToken
        } while (token != null)
        return out
    }

    class Summary(val avgHr: Int?, val distanceM: Double?, val calories: Int?)

    /** A szakasz összesítői, csak az edzést rögzítő app adataiból (hogy a telefon lépésszámlálója ne duplázzon). */
    suspend fun summary(s: ExerciseSessionRecord): Summary {
        val r = client.aggregate(
            AggregateRequest(
                setOf(HeartRateRecord.BPM_AVG, DistanceRecord.DISTANCE_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                TimeRangeFilter.between(s.startTime, s.endTime), setOf(s.metadata.dataOrigin),
            )
        )
        return Summary(
            r[HeartRateRecord.BPM_AVG]?.toInt(),
            r[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.takeIf { it > 0 },
            r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.toInt(),
        )
    }

    suspend fun heartRates(startMs: Long, endMs: Long): List<TrackPoint> {
        val out = mutableListOf<TrackPoint>()
        var token: String? = null
        do {
            val r = client.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs)), pageToken = token,
                )
            )
            r.records.forEach { rec -> rec.samples.forEach { out += TrackPoint(it.time.toEpochMilli(), hr = it.beatsPerMinute.toInt()) } }
            token = r.pageToken
        } while (token != null)
        return out.filter { it.timeMs in startMs..endMs }
    }

    suspend fun route(hcId: String): ExerciseRouteResult =
        client.readRecord(ExerciseSessionRecord::class, hcId).record.exerciseRouteResult

    /** Hány edzés látszik az elmúlt 30 napból, és mely appok írták (hibakereséshez a Beállításokban). */
    suspend fun diagnose(): String {
        val list = sessions(Instant.now().minusSeconds(30 * 86400L))
        if (list.isEmpty()) return "Az elmúlt 30 napból egy edzés sem látszik a Health Connectben. A Google Health appban " +
            "kapcsold be a Health Connect megosztást (edzések, pulzus, távolság), majd szinkronizálj."
        return "${list.size} edzés látszik az elmúlt 30 napból. Forrás: " + list.map { it.metadata.dataOrigin.packageName }.distinct().joinToString()
    }

    companion object {
        val PERMS = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        )
        /** A háttérszinkronhoz kell; kérjük, de nem feltétel. */
        val REQUEST = PERMS + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

        fun sportOf(type: Int) = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "bike"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "walk"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
            else -> "other"
        }
    }
}
