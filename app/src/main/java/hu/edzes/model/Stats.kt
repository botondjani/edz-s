package hu.edzes.model

import hu.edzes.data.Activity
import hu.edzes.data.Planned
import hu.edzes.data.SetLog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

/** Pulzuszóna a max pulzus százalékában: Z1 50–60%, Z2 60–70%, Z3 70–80%, Z4 80–90%, Z5 90% fölött; 0 = Z1 alatt. */
fun zoneOf(bpm: Int, maxHr: Int): Int = when (bpm * 100 / maxHr) {
    in 0..49 -> 0; in 50..59 -> 1; in 60..69 -> 2; in 70..79 -> 3; in 80..89 -> 4; else -> 5
}

/** Célzóna a szövegből ("Z4, 4:30/km" -> 4). */
fun targetZone(text: String?): Int? =
    text?.let { Regex("\\bZ([1-5])\\b", RegexOption.IGNORE_CASE).find(it) }?.groupValues?.get(1)?.toInt()

/**
 * Pulzus-hisztogram 5-ös bpm sávokban: "140:320,145:95" = 320 mp 140–144 között.
 * Így a zónaidők bármikor újraszámolhatók, ha a max pulzus beállítása változik.
 */
fun hrHistogram(samples: List<Pair<Long, Int>>): String? {
    val sorted = samples.sortedBy { it.first }
    val ms = sortedMapOf<Int, Long>()
    for (i in 0 until sorted.size - 1) {
        val bucket = sorted[i].second / 5 * 5
        ms[bucket] = (ms[bucket] ?: 0) + min(sorted[i + 1].first - sorted[i].first, 30_000)   // kihagyásnál max 30 mp számít
    }
    return ms.filterValues { it >= 1000 }.entries.joinToString(",") { "${it.key}:${it.value / 1000}" }.ifEmpty { null }
}

/** Zónánként töltött másodpercek (index = zóna, 0 = Z1 alatt). */
fun zoneSeconds(hist: String?, maxHr: Int): IntArray? {
    if (hist.isNullOrEmpty()) return null
    val out = IntArray(6)
    for (part in hist.split(',')) {
        val (bpm, sec) = part.split(':').map { it.toInt() }
        out[zoneOf(bpm + 2, maxHr)] += sec
    }
    return out
}

/**
 * Edzésterhelés: zónákban töltött percek zónaszámmal súlyozva (Z1 alatt 0,5).
 * Részletes pulzus nélkül az átlagpulzus zónájával, pulzus nélkül közepes (2-es) intenzitással számol.
 */
fun trainingLoad(a: Activity, maxHr: Int?): Double {
    val minutes = a.durationS / 60.0
    if (maxHr == null) return minutes * 2
    zoneSeconds(a.hrHist, maxHr)?.let { z -> return z.withIndex().sumOf { (zone, s) -> s / 60.0 * if (zone == 0) 0.5 else zone.toDouble() } }
    return minutes * (a.avgHr?.let { maxOf(zoneOf(it, maxHr), 1) } ?: 2)
}

/** Edzettség (42 napos) és fáradtság (7 napos) a napi terhelések exponenciális átlagából; forma = a kettő különbsége. */
class Form(val fitness: Double, val fatigue: Double) { val form get() = fitness - fatigue }

fun formSeries(dailyLoad: List<Double>): List<Form> {
    var fitness = 0.0
    var fatigue = 0.0
    return dailyLoad.map { load ->
        fitness += (load - fitness) / 42
        fatigue += (load - fatigue) / 7
        Form(fitness, fatigue)
    }
}

/** Szól, ha a pulzus tartósan a célzónán kívül van. A lépés első 45 mp-ében türelmes (a pulzus késve követ). */
class ZoneCoach {
    private var outSince: Long? = null
    private var lastAlert = -60_000L

    fun check(nowMs: Long, stepMs: Long, zone: Int, target: Int?): String? {
        if (target == null || zone == target || stepMs < 45_000) { outSince = null; return null }
        val since = outSince ?: nowMs.also { outSince = it }
        if (nowMs - since < 15_000 || nowMs - lastAlert < 45_000) return null
        lastAlert = nowMs
        return if (zone > target) "Pulzus magas, lassíts" else "Pulzus alacsony, gyorsíts"
    }
}

/** Két GPS pont távolsága méterben. */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
    return 6_371_000 * 2 * Math.asin(Math.sqrt(a))
}

/** Tempó (mp/km) kb. 30 mp-es ablakokban a GPS pontokból: (idő ms, szélesség, hosszúság). Az álló szakaszok kimaradnak. */
fun paceSeries(points: List<Triple<Long, Double, Double>>): List<Float> {
    val out = mutableListOf<Float>()
    var start = 0
    var dist = 0.0
    for (i in 1 until points.size) {
        dist += haversineM(points[i - 1].second, points[i - 1].third, points[i].second, points[i].third)
        val ms = points[i].first - points[start].first
        if (ms >= 30_000) {
            if (dist >= 15) out += (ms / dist).toFloat()
            start = i; dist = 0.0
        }
    }
    return out
}

fun dayOfMs(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
fun LocalDate.monday(): LocalDate = with(DayOfWeek.MONDAY)

private fun clock(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
private fun km(m: Double) = "%.2f km".format(m / 1000).replace('.', ',')

/** A sorozatok / részidők szövegesen, gyakorlatonként egy sor. */
fun describe(logs: List<SetLog>): String = logs.groupBy { it.exercise }.entries.joinToString("\n") { (exercise, sets) ->
    "$exercise: " + sets.joinToString(", ") { s ->
        when {
            s.distanceM != null && s.durationS != null -> "${fmtMeters(s.distanceM.toInt())} ${clock(s.durationS)}"
            s.durationS != null -> fmtSecs(s.durationS)
            s.weightKg != null -> "${s.reps}×${fmtKg(s.weightKg)}"
            else -> "${s.reps} ism."
        }
    }
}

/** Elemzésre kész edzésnapló Markdownban (Claude-nak megosztáshoz): heti összesítő, edzések részletesen, terv. */
fun trainingReport(
    activities: List<Activity>, logs: Map<Long, List<SetLog>>, planned: List<Planned>, maxHr: Int?, today: LocalDate, weeks: Int,
): String {
    val from = today.monday().minusWeeks(weeks - 1L)
    val acts = activities.filter { !it.hidden && dayOfMs(it.startMs) >= from }.sortedBy { it.startMs }
    val sb = StringBuilder("# Edzésnapló — utolsó $weeks hét ($from – $today)\n\n")
    sb.append("Max pulzus: ${maxHr ?: "nincs megadva"}. Zónák a max pulzus %-ában: Z1 50–60, Z2 60–70, Z3 70–80, Z4 80–90, Z5 90+.\n")
    sb.append("Terhelés = zónákban töltött percek zónaszámmal súlyozva.\n\n## Heti összesítő\n\n")
    sb.append("| Hét kezdete | Edzés | Idő | Futás | Kerékpár | Terhelés | Terv teljesítve |\n|---|---|---|---|---|---|---|\n")
    for (w in 0 until weeks) {
        val start = from.plusWeeks(w.toLong())
        val week = acts.filter { dayOfMs(it.startMs).monday() == start }
        val plans = planned.filter { LocalDate.ofEpochDay(it.date).monday() == start }
        fun dist(sport: String) = km(week.filter { it.sport == sport }.sumOf { it.distanceM ?: 0.0 })
        sb.append("| $start | ${week.size} | ${clock(week.sumOf { it.durationS })} | ${dist("run")} | ${dist("bike")} | ")
        sb.append("${week.sumOf { trainingLoad(it, maxHr) }.toInt()} | ${plans.count { it.status == "DONE" }}/${plans.size} |\n")
    }
    sb.append("\n## Edzések\n")
    for (a in acts) {
        sb.append("\n### ${dayOfMs(a.startMs)} — ${sportName(a.sport)}: ${a.title}\n")
        val parts = mutableListOf("idő ${clock(a.durationS)}")
        a.distanceM?.let {
            parts += "táv ${km(it)}"
            if (it > 0 && a.sport != "bike") parts += "tempó ${clock((a.durationS / (it / 1000)).toInt())}/km"
            if (it > 0 && a.sport == "bike") parts += "%.1f km/h".format(it / 1000 / (a.durationS / 3600.0))
        }
        a.avgHr?.let { parts += "átlagpulzus $it" }
        a.calories?.let { parts += "$it kcal" }
        sb.append("- ${parts.joinToString(", ")}\n")
        maxHr?.let { zoneSeconds(a.hrHist, it) }?.let { z ->
            sb.append("- zónaidők: " + (1..5).filter { z[it] >= 30 }.joinToString(", ") { "Z$it ${clock(z[it])}" } + "\n")
        }
        logs[a.id]?.takeIf { it.isNotEmpty() }?.let { sb.append(describe(it).lines().joinToString("\n") { l -> "- $l" } + "\n") }
    }
    val upcoming = planned.filter { it.date >= today.toEpochDay() && it.status == "PLANNED" }.sortedBy { it.date }.take(30)
    if (upcoming.isNotEmpty()) {
        sb.append("\n## Következő tervezett edzések\n\n")
        for (p in upcoming) {
            val w = runCatching { kotlinx.serialization.json.Json.decodeFromString<Workout>(p.workoutJson) }.getOrNull()
            sb.append("- ${LocalDate.ofEpochDay(p.date)} — ${sportName(p.sport)}: ${p.title}" + (w?.summary()?.joinToString("; ")?.let { " ($it)" } ?: "") + "\n")
        }
    }
    val missed = planned.filter { LocalDate.ofEpochDay(it.date) >= from && it.date < today.toEpochDay() && it.status != "DONE" }
    if (missed.isNotEmpty()) {
        sb.append("\n## Kihagyott / el nem végzett tervek\n\n")
        missed.forEach { sb.append("- ${LocalDate.ofEpochDay(it.date)} — ${sportName(it.sport)}: ${it.title}\n") }
    }
    return sb.toString()
}
