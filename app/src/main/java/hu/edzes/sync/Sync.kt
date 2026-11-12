package hu.edzes.sync

import android.content.Context
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import hu.edzes.dao
import hu.edzes.data.Activity
import hu.edzes.data.DONE
import hu.edzes.data.EdzesDao
import hu.edzes.data.SKIPPED
import hu.edzes.data.SetLog
import hu.edzes.data.Tracks
import hu.edzes.model.describe
import hu.edzes.model.hrHistogram
import hu.edzes.model.sportGroup
import hu.edzes.model.sportName
import hu.edzes.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun overlaps(aStartMs: Long, aDurS: Int, bStartMs: Long, bDurS: Int): Boolean =
    aStartMs < bStartMs + maxOf(bDurS, 60) * 1000L && bStartMs < aStartMs + maxOf(aDurS, 60) * 1000L

class StravaPlan(val links: List<Pair<Activity, Long>>, val uploads: List<Activity>, val imports: List<StravaActivity>)

/** Mi a teendő a Stravával: időben átfedő edzéseket összekapcsol, a többit feltölti / letölti. */
fun planStrava(local: List<Activity>, remote: List<StravaActivity>): StravaPlan {
    val known = local.mapNotNull { it.stravaId }.toSet()
    val free = remote.filter { it.id !in known }.toMutableList()
    val links = mutableListOf<Pair<Activity, Long>>()
    val uploads = mutableListOf<Activity>()
    for (a in local.filter { it.stravaId == null }) {
        val match = free.firstOrNull { overlaps(a.startMs, a.durationS, it.startMs, it.durationS) }
        if (match != null) { free -= match; links += a to match.id } else uploads += a
    }
    return StravaPlan(links, uploads, free)
}

/** A tevékenységet a napi, azonos sportú, még nem párosított tervhez köti. */
suspend fun matchPlan(dao: EdzesDao, a: Activity) {
    val date = Instant.ofEpochMilli(a.startMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    val p = dao.plannedOn(date).firstOrNull {
        it.activityId == null && it.status != SKIPPED && sportGroup(it.sport) == sportGroup(a.sport)
    } ?: return
    dao.update(p.copy(status = DONE, activityId = a.id))
}

object Sync {
    val status = MutableStateFlow("")
    val running = MutableStateFlow(false)
    private val mutex = Mutex()
    private const val WINDOW_DAYS = 30L
    private const val DAY_MS = 86_400_000L

    suspend fun run(ctx: Context) {
        if (!mutex.tryLock()) return
        running.value = true
        val notes = mutableListOf<String?>()
        try {
            notes += step("Health Connect") { importHealthConnect(ctx) }
            notes += step("Strava") { syncStrava(ctx) }
        } finally {
            status.value = LocalTime.now().truncatedTo(ChronoUnit.MINUTES).toString() + " · " +
                notes.filterNotNull().ifEmpty { listOf("nincs összekapcsolt szolgáltatás") }.joinToString(" · ")
            running.value = false
            mutex.unlock()
        }
    }

    private suspend fun step(name: String, block: suspend () -> String?): String? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        "$name: nincs kapcsolat"
    } catch (e: Exception) {
        "$name hiba: ${e.message}"
    }

    private suspend fun importHealthConnect(ctx: Context): String? {
        if (!Hc.available(ctx)) return null
        val hc = Hc(ctx)
        if (!hc.granted()) return null
        val dao = ctx.dao
        val since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS)
        val existing = dao.activitiesSince(since.toEpochMilli() - DAY_MS).toMutableList()
        var added = 0
        for (s in hc.sessions(since)) {
            if (existing.any { it.hcId == s.metadata.id }) continue
            val startMs = s.startTime.toEpochMilli()
            val durationS = ChronoUnit.SECONDS.between(s.startTime, s.endTime).toInt()
            val sum = hc.summary(s)
            val hist = hrHistogram(hc.heartRates(startMs, s.endTime.toEpochMilli()).map { it.timeMs to it.hr!! })
            val i = existing.indexOfFirst { it.hcId == null && overlaps(it.startMs, it.durationS, startMs, durationS) }
            if (i >= 0) {
                // Ugyanaz az edzés már megvan (az appban mérve vagy Stravából): csak kiegészítjük az óra adataival.
                val e = existing[i]
                existing[i] = e.copy(
                    hcId = s.metadata.id, avgHr = e.avgHr ?: sum.avgHr,
                    calories = e.calories ?: sum.calories, distanceM = e.distanceM ?: sum.distanceM, hrHist = e.hrHist ?: hist,
                )
                dao.update(existing[i])
            } else {
                val sport = Hc.sportOf(s.exerciseType)
                val a = Activity(
                    source = "hc", sport = sport, title = s.title ?: sportName(sport), startMs = startMs, durationS = durationS,
                    distanceM = sum.distanceM, avgHr = sum.avgHr, calories = sum.calories, hcId = s.metadata.id, hrHist = hist,
                )
                val saved = a.copy(id = dao.insert(a))
                existing += saved
                matchPlan(dao, saved)
                added++
            }
        }
        // A korábban (pulzusadat nélkül vagy régebbi verzióval) átvett edzések zónaadatainak pótlása.
        for (e in existing.filter { it.hrHist == null && it.hcId != null }) {
            val hist = hrHistogram(hc.heartRates(e.startMs, e.startMs + e.durationS * 1000L).map { it.timeMs to it.hr!! }) ?: continue
            dao.activity(e.id)?.let { dao.update(it.copy(hrHist = hist)) }
        }
        return "Health Connect: $added új"
    }

    private suspend fun syncStrava(ctx: Context): String? {
        val strava = Strava(ctx)
        if (!strava.connected) return null
        val dao = ctx.dao
        val sinceMs = System.currentTimeMillis() - WINDOW_DAYS * DAY_MS
        val plan = planStrava(dao.activitiesSince(sinceMs - DAY_MS), strava.activities(sinceMs / 1000))
        plan.links.forEach { (a, id) -> dao.update(a.copy(stravaId = id)) }
        for (r in plan.imports) {
            val a = Activity(
                source = "strava", sport = r.sport, title = r.name, startMs = r.startMs, durationS = r.durationS,
                distanceM = r.distanceM, avgHr = r.avgHr, stravaId = r.id,
            )
            matchPlan(dao, a.copy(id = dao.insert(a)))
        }
        var uploaded = 0
        if (ctx.prefs.getBoolean("auto_upload", true)) for (a in plan.uploads) {
            // Az összekapcsolás előtti edzéseket és a sétákat nem töltjük fel magunktól.
            val skip = a.hidden || a.needsRoute || a.stravaError != null || a.startMs < strava.connectedAtMs ||
                (a.sport == "walk" && !ctx.prefs.getBoolean("upload_walks", false))
            if (!skip && upload(ctx, a)) uploaded++
        }
        return "Strava: ${plan.imports.size} letöltve, $uploaded feltöltve"
    }

    /**
     * Feltölt egy edzést a Stravára. GPS-szel/pulzussal TCX-ként, különben kézi tevékenységként.
     * Ha az útvonalhoz még engedély kell, megjelöli az edzést és false-t ad vissza.
     * Hálózati hibánál IOException-t dob (később újrapróbálható).
     */
    suspend fun upload(
        ctx: Context, activity: Activity, route: ExerciseRoute? = null, routeDenied: Boolean = false, force: Boolean = false,
    ): Boolean {
        val dao = ctx.dao
        var a = activity
        try {
            val hcOk = Hc.available(ctx) && runCatching { Hc(ctx).granted() }.getOrDefault(false)
            val points = try {
                trackPoints(ctx, a, route, routeDenied, hcOk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Az óra által mért edzést adatok nélkül nem töltjük fel; az appban mértnél a pulzus csak ráadás.
                if (a.hcId != null && a.source == "hc") return false else emptyList()
            }
            if (points == null) {
                dao.update(a.copy(needsRoute = true))
                return false
            }
            // Az appban mért edzéshez az óra pulzusa késve érkezik a Health Connectbe: fél óráig várunk rá.
            val endMs = a.startMs + a.durationS * 1000L
            if (!force && a.source == "app" && hcOk && points.none { it.hr != null } && System.currentTimeMillis() - endMs < 30 * 60_000) return false
            a = a.copy(needsRoute = false)
            val description = describe(dao.setLogs(a.id))
            val strava = Strava(ctx)
            val id = if (points.isEmpty()) strava.createManual(a, description)
            else strava.uploadTcx(buildTcx(a.sport, a.startMs, a.durationS, a.distanceM, a.calories, points), a, description)
            dao.update(a.copy(stravaId = id, stravaError = null))
            return true
        } catch (e: StravaException) {
            dao.update(a.copy(stravaError = e.message))
            return false
        }
    }

    /** Az appban rögzített útvonal, kiegészítve a Health Connect adataival. null = az útvonalhoz a felhasználó engedélye kell. */
    private suspend fun trackPoints(ctx: Context, a: Activity, given: ExerciseRoute?, routeDenied: Boolean, hcOk: Boolean): List<TrackPoint>? {
        val local = Tracks.read(ctx, a.id)
        if (!hcOk || local.any { it.hr != null }) return local
        val hc = Hc(ctx)
        var route = given
        if (local.isEmpty() && route == null && a.hcId != null && !routeDenied) when (val r = hc.route(a.hcId)) {
            is ExerciseRouteResult.Data -> route = r.exerciseRoute
            is ExerciseRouteResult.ConsentRequired -> return null
            else -> {}
        }
        val gps = local.ifEmpty {
            route?.route.orEmpty().map { TrackPoint(it.time.toEpochMilli(), it.latitude, it.longitude, it.altitude?.inMeters) }
        }
        return mergePoints(gps, hc.heartRates(a.startMs, a.startMs + a.durationS * 1000L))
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.run(applicationContext)
        return Result.success()
    }
}
