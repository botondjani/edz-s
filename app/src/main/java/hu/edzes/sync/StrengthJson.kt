package hu.edzes.sync

import hu.edzes.data.SetLog
import hu.edzes.model.StravaExercises
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId

/** Rövid Strava-leírás erősítő edzéshez: csak a megdolgozott testtájak, gyakorlatok és súlyok nélkül. */
fun strengthDescription(logs: List<SetLog>): String {
    val areas = strengthSets(logs).map { StravaExercises.area(StravaExercises.of(it.exercise, it.strava)) }.distinct()
    return "Erősítő edzés" + if (areas.isEmpty()) "" else " – " + areas.joinToString(", ")
}

/** A naplóból a Strava által sorozatként elfogadott tételek: ismétléses vagy időre végzett gyakorlatok (a kardió részidők nem). */
fun strengthSets(logs: List<SetLog>) = logs.filter { it.distanceM == null && ((it.reps ?: 0) > 0 || (it.durationS ?: 0) > 0) }

/**
 * Súlyzós / HIIT edzés a Strava strength JSON formátumában (developers.strava.com/docs/uploads):
 * gyakorlatonkénti sorozatok és – ha van – pulzusgörbe. Ebből a Strava a sorozatokat és az izomtérképet mutatja.
 */
fun buildStrengthJson(
    startMs: Long, durationS: Int, calories: Int?, logs: List<SetLog>, hr: List<TrackPoint>, zone: ZoneId = ZoneId.systemDefault(),
): JsonObject = buildJsonObject {
    // Másodpercre kerekítve: a tört másodperces időbélyeget a Strava nem mindig fogadja el.
    val start = Instant.ofEpochMilli(startMs).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
    put("version", "1.0")
    put("start_time", start.toString())
    put("utc_offset", zone.rules.getOffset(start).totalSeconds)
    put("elapsed_time", durationS)
    calories?.let { put("total_calories", it) }
    putJsonObject("creator") { put("name", "Edzés app") }
    // Másodpercenként legfeljebb egy pulzusérték, az edzés idején belül.
    val samples = hr.filter { it.hr != null && it.timeMs >= startMs }
        .map { ((it.timeMs - startMs) / 1000).toInt() to it.hr!! }
        .filter { it.first <= durationS }.distinctBy { it.first }.sortedBy { it.first }
    if (samples.size >= 2) putJsonObject("streams") {
        putJsonArray("time") { samples.forEach { add(it.first) } }
        putJsonArray("heartrate") { samples.forEach { add(it.second) } }
    }
    putJsonArray("sets") {
        for (s in strengthSets(logs)) add(buildJsonObject {
            put("exercise_type", StravaExercises.of(s.exercise, s.strava))
            s.reps?.takeIf { it > 0 }?.let { put("repetitions", it) }
            s.weightKg?.takeIf { it > 0 }?.let { put("weight", it) }
            s.durationS?.takeIf { it > 0 }?.let { put("duration", it) }
        })
    }
}
