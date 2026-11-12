package hu.edzes.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** Normalizált blokk. type: sets | timed | interval | segment | repeat */
@Serializable
data class Block(
    val type: String,
    val name: String = "",
    val sets: Int = 1,            // sorozat / kör / ismétlésszám (repeat)
    val reps: String? = null,
    val weightKg: Double? = null,
    val restS: Int = 0,
    val durationS: Int? = null,
    val distanceM: Int? = null,
    val target: String? = null,
    val blocks: List<Block> = emptyList(),
)

@Serializable
data class Workout(
    val title: String,
    val sport: String,
    val notes: String? = null,
    val target: String? = null,
    val blocks: List<Block> = emptyList(),
)

data class DaySpec(val week: Int, val weekday: Int, val workout: Workout)
data class ProgramSpec(val name: String, val weeks: Int, val days: List<DaySpec>)

/** A program napjainak dátuma: az 1. hét a megadott hétfővel kezdődik. */
fun ProgramSpec.dates(startMonday: LocalDate): List<Pair<LocalDate, Workout>> =
    days.map { startMonday.plusDays((it.week - 1) * 7L + it.weekday - 1) to it.workout }

object ProgramParser {
    const val FORMAT = "edzes-program/v1"
    val SPORTS = listOf("run", "bike", "swim", "strength", "hiit", "walk", "other")
    private val WEEKDAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    class Result(val program: ProgramSpec?, val errors: List<String>)

    /** A szövegben lévő első JSON objektumot olvassa be (a körülötte lévő szöveget, ``` jeleket eldobja). */
    fun parse(text: String): Result {
        val from = text.indexOf('{')
        val to = text.lastIndexOf('}')
        if (from < 0 || to < from) return Result(null, listOf("Nem található JSON objektum a szövegben."))
        val root = try {
            Json.parseToJsonElement(text.substring(from, to + 1)) as JsonObject
        } catch (e: SerializationException) {
            return Result(null, listOf("Hibás JSON: " + e.message.orEmpty().lineSequence().first()))
        }
        val v = Validator()
        val format = v.str(root, "program", "format")
        if (format != null && format != FORMAT) v.errors += "program.format: \"$FORMAT\" kell, de \"$format\" van"
        val name = v.str(root, "program", "name")
        val dayList = root["days"] as? JsonArray
        if (dayList == null || dayList.isEmpty()) v.errors += "program: hiányzik vagy üres a \"days\" lista"
        val days = dayList.orEmpty().mapIndexedNotNull { i, d -> v.day(d as? JsonObject, "days[$i]") }
        if (v.errors.isNotEmpty() || name == null) return Result(null, v.errors)
        val weeks = maxOf(v.int(root, "program", "weeks", required = false, min = 1) ?: 1, days.maxOf { it.week })
        return Result(ProgramSpec(name, weeks, days), emptyList())
    }

    private class Validator {
        val errors = mutableListOf<String>()

        private fun prim(o: JsonObject, path: String, key: String, required: Boolean): JsonPrimitive? {
            val p = o[key]
            if (p is JsonPrimitive && p !is JsonNull) return p
            if (p != null && p !is JsonNull) errors += "$path.$key: egyszerű érték kell"
            else if (required) errors += "$path: hiányzik a \"$key\" mező"
            return null
        }

        fun str(o: JsonObject, path: String, key: String, required: Boolean = true): String? =
            prim(o, path, key, required)?.content?.trim()?.also {
                if (it.isEmpty()) errors += "$path.$key: nem lehet üres"
            }

        fun num(o: JsonObject, path: String, key: String, required: Boolean = true, min: Double = 0.0): Double? {
            val p = prim(o, path, key, required) ?: return null
            val d = p.content.toDoubleOrNull()?.takeIf { !p.isString && it >= min }
            if (d == null) errors += "$path.$key: szám kell (legalább ${min.toInt()}), de \"${p.content}\" van"
            return d
        }

        fun int(o: JsonObject, path: String, key: String, required: Boolean = true, min: Int = 0): Int? {
            val d = num(o, path, key, required, min.toDouble()) ?: return null
            if (d % 1.0 != 0.0) { errors += "$path.$key: egész szám kell"; return null }
            return d.toInt()
        }

        fun day(o: JsonObject?, path: String): DaySpec? {
            if (o == null) { errors += "$path: objektum kell"; return null }
            val week = int(o, path, "week", min = 1)
            val wd = str(o, path, "weekday")?.uppercase()
            val weekday = WEEKDAYS.indexOf(wd) + 1
            if (wd != null && weekday == 0) errors += "$path.weekday: ${WEEKDAYS.joinToString("/")} egyike kell, de \"$wd\" van"
            val w = o["workout"] as? JsonObject
            if (w == null) { errors += "$path: hiányzik a \"workout\" objektum"; return null }
            val workout = workout(w, "$path.workout")
            return if (week != null && weekday > 0 && workout != null) DaySpec(week, weekday, workout) else null
        }

        private fun workout(o: JsonObject, path: String): Workout? {
            val title = str(o, path, "title")
            val sport = str(o, path, "sport")?.lowercase()
            if (sport != null && sport !in SPORTS) errors += "$path.sport: ${SPORTS.joinToString("/")} egyike kell, de \"$sport\" van"
            val blocks = blocks(o, path)
            return if (title != null && sport != null && blocks != null)
                Workout(title, sport, str(o, path, "notes", required = false), target(o, path), blocks) else null
        }

        /** A target lehet szöveg vagy objektum (duration_min, distance_km, hr_zone, pace, note). */
        private fun target(o: JsonObject, path: String): String? = when (val t = o["target"]) {
            is JsonObject -> listOfNotNull(
                num(t, "$path.target", "duration_min", required = false)?.let { "${fmt(it)} perc" },
                num(t, "$path.target", "distance_km", required = false)?.let { "${fmt(it)} km" },
                str(t, "$path.target", "hr_zone", required = false)?.let { "Z${it.removePrefix("Z").removePrefix("z")}" },
                str(t, "$path.target", "pace", required = false),
                str(t, "$path.target", "note", required = false),
            ).joinToString(" · ").ifEmpty { null }
            null, is JsonNull -> null
            else -> str(o, path, "target", required = false)
        }

        private fun blocks(o: JsonObject, path: String): List<Block>? {
            val arr = when (val b = o["blocks"]) {
                null, is JsonNull -> return emptyList()
                is JsonArray -> b
                else -> { errors += "$path.blocks: lista kell"; return null }
            }
            val out = arr.mapIndexed { i, b -> block(b as? JsonObject, "$path.blocks[$i]") }
            return if (out.any { it == null }) null else out.filterNotNull()
        }

        private fun block(o: JsonObject?, path: String): Block? {
            if (o == null) { errors += "$path: objektum kell"; return null }
            val before = errors.size
            val block = when (val type = str(o, path, "type")) {
                "sets" -> Block(
                    "sets", str(o, path, "exercise").orEmpty(), int(o, path, "sets", min = 1) ?: 1,
                    reps = str(o, path, "reps"), weightKg = num(o, path, "weight_kg", required = false),
                    restS = int(o, path, "rest_s", required = false) ?: 0,
                )
                "timed" -> Block(
                    "timed", str(o, path, "exercise").orEmpty(), int(o, path, "sets", required = false, min = 1) ?: 1,
                    durationS = int(o, path, "duration_s", min = 1), restS = int(o, path, "rest_s", required = false) ?: 0,
                )
                "interval" -> {
                    val dur = int(o, path, "work_s", required = false, min = 1)
                    val dist = int(o, path, "work_m", required = false, min = 1)
                    if ((dur == null) == (dist == null)) errors += "$path: a \"work_s\" és a \"work_m\" közül pontosan egy kell"
                    Block(
                        "interval", str(o, path, "name", required = false) ?: "Intervall", int(o, path, "rounds", min = 1) ?: 1,
                        durationS = dur, distanceM = dist, restS = int(o, path, "rest_s") ?: 0,
                        target = str(o, path, "target", required = false),
                    )
                }
                "segment" -> {
                    val dur = int(o, path, "duration_s", required = false, min = 1)
                    val dist = int(o, path, "distance_m", required = false, min = 1)
                    if ((dur == null) == (dist == null)) errors += "$path: a \"duration_s\" és a \"distance_m\" közül pontosan egy kell"
                    Block("segment", str(o, path, "name").orEmpty(), durationS = dur, distanceM = dist, target = str(o, path, "target", required = false))
                }
                "repeat" -> {
                    val inner = blocks(o, path)
                    if (inner != null && inner.isEmpty()) errors += "$path: hiányzik vagy üres a \"blocks\" lista"
                    Block("repeat", sets = int(o, path, "times", min = 1) ?: 1, blocks = inner.orEmpty())
                }
                null -> null
                else -> { errors += "$path.type: sets/timed/interval/segment/repeat egyike kell, de \"$type\" van"; null }
            }
            return block.takeIf { errors.size == before }
        }
    }
}

private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString().replace('.', ',')

fun fmtKg(kg: Double) = fmt(kg) + " kg"

/** Időtartam bemondható/olvasható formában: "45 mp", "5 perc", "1 perc 30 mp". */
fun fmtSecs(s: Int) = when {
    s < 60 -> "$s mp"
    s % 60 == 0 -> "${s / 60} perc"
    else -> "${s / 60} perc ${s % 60} mp"
}

fun fmtMeters(m: Int) = if (m >= 1000) fmt(m / 1000.0) + " km" else "$m m"

/** Egy sor blokkonként, az edzés előnézetéhez. */
fun Workout.summary(): List<String> = listOfNotNull(target) + blocks.map { it.summary() }

private fun Block.summary(): String = when (type) {
    "sets" -> "$name — $sets × $reps" + (weightKg?.let { " @ ${fmtKg(it)}" } ?: "") + restText()
    "timed" -> "$name — $sets × ${fmtSecs(durationS ?: 0)}" + restText()
    "interval" -> "$name — $sets × ${length()} / pihenő ${fmtSecs(restS)}" + targetText()
    "segment" -> "$name — ${length()}" + targetText()
    else -> "$sets× [" + blocks.joinToString(" + ") { it.summary() } + "]"
}

private fun Block.length() = durationS?.let(::fmtSecs) ?: fmtMeters(distanceM ?: 0)
private fun Block.targetText() = target?.let { " · $it" } ?: ""
private fun Block.restText() = if (restS > 0) ", pihenő ${fmtSecs(restS)}" else ""

/** A lejátszó egy lépése. Ha nincs időtartam és táv, a felhasználó lépteti (súlyzós sorozat). */
data class Step(
    val title: String,
    val info: String = "",
    val rest: Boolean = false,
    val durationS: Int? = null,
    val distanceM: Int? = null,
    val exercise: String? = null,   // naplózandó gyakorlat
    val reps: String? = null,
    val weightKg: Double? = null,
) {
    val manual get() = durationS == null && distanceM == null
}

fun Workout.steps(): List<Step> = flatten(blocks, "").let { if (it.lastOrNull()?.rest == true) it.dropLast(1) else it }

fun flatten(blocks: List<Block>, prefix: String = ""): List<Step> = blocks.flatMap { b ->
    val rest = if (b.restS > 0) Step("Pihenő", rest = true, durationS = b.restS) else null
    when (b.type) {
        "sets" -> (1..b.sets).flatMap { i ->
            val plan = b.reps + " ism." + (b.weightKg?.let { " × ${fmtKg(it)}" } ?: "")
            listOfNotNull(Step(b.name, "$prefix$i/${b.sets}. sorozat · $plan", exercise = b.name, reps = b.reps, weightKg = b.weightKg), rest)
        }
        "timed" -> (1..b.sets).flatMap { i ->
            listOfNotNull(Step(b.name, "$prefix$i/${b.sets}. sorozat", durationS = b.durationS, exercise = b.name), rest)
        }
        "interval" -> (1..b.sets).flatMap { i ->
            listOfNotNull(Step(b.name, "$prefix$i/${b.sets}. kör" + (b.target?.let { " · $it" } ?: ""), durationS = b.durationS, distanceM = b.distanceM), rest)
        }
        "segment" -> listOf(Step(b.name, prefix + b.target.orEmpty(), durationS = b.durationS, distanceM = b.distanceM))
        "repeat" -> (1..b.sets).flatMap { i -> flatten(b.blocks, "$prefix$i/${b.sets}. kör · ") }
        else -> emptyList()
    }
}

/** Az intervall-szerkesztő edzése: bemelegítés, N × (munka idő vagy táv szerint + pihenő), levezetés. Teremben csak a körök. */
fun intervalWorkout(
    sport: String, name: String, warmupMin: Int, work: Int, byDistance: Boolean, restS: Int, rounds: Int, cooldownMin: Int,
): Workout {
    val gym = !isCardio(sport)
    fun easy(label: String, minutes: Int) = if (minutes > 0 && !gym) Block("segment", label, durationS = minutes * 60) else null
    val interval = Block(
        "interval", if (gym) "Munka" else "Gyors", sets = rounds, restS = restS,
        durationS = work.takeIf { !byDistance }, distanceM = work.takeIf { byDistance },
    )
    val title = name.trim().ifEmpty { "$rounds × $work ${if (byDistance) "m" else "mp"} / $restS mp" }
    return Workout(title, sport, blocks = listOfNotNull(easy("Bemelegítés", warmupMin), interval, easy("Levezetés", cooldownMin)))
}

const val CLAUDE_PROMPT = """Készíts edzésprogramot az alábbi JSON formátumban. Csak a JSON-t add vissza, egyetlen kódblokkban.

{
  "format": "edzes-program/v1",
  "name": "Program neve",
  "weeks": 4,
  "days": [
    { "week": 1, "weekday": "MON", "workout": {
        "title": "Felsőtest A", "sport": "strength", "notes": "opcionális megjegyzés",
        "blocks": [
          { "type": "sets", "exercise": "Fekvenyomás", "sets": 4, "reps": 8, "weight_kg": 60, "rest_s": 120 },
          { "type": "timed", "exercise": "Plank", "duration_s": 60, "sets": 3, "rest_s": 30 },
          { "type": "interval", "name": "Tabata burpee", "work_s": 20, "rest_s": 10, "rounds": 8 },
          { "type": "repeat", "times": 3, "blocks": [
              { "type": "sets", "exercise": "Húzódzkodás", "sets": 1, "reps": "max", "rest_s": 60 } ] }
        ] } },
    { "week": 1, "weekday": "WED", "workout": {
        "title": "Intervall futás", "sport": "run",
        "blocks": [
          { "type": "segment", "name": "Bemelegítés", "duration_s": 600, "target": "Z2" },
          { "type": "interval", "name": "Gyors", "work_m": 400, "rest_s": 90, "rounds": 6, "target": "Z5" },
          { "type": "repeat", "times": 5, "blocks": [
              { "type": "segment", "name": "Gyors", "distance_m": 1000, "target": "Z4, 4:30/km" },
              { "type": "segment", "name": "Kocogás", "duration_s": 120, "target": "Z1" } ] },
          { "type": "segment", "name": "Levezetés", "duration_s": 300 }
        ] } },
    { "week": 1, "weekday": "SAT", "workout": {
        "title": "Könnyű futás", "sport": "run", "target": { "duration_min": 40, "hr_zone": 2 } } }
  ]
}

Szabályok:
- sport: run | bike | swim | strength | hiit | walk | other
- weekday: MON | TUE | WED | THU | FRI | SAT | SUN; week: 1-től számozva. Minden hét minden edzését írd ki külön (a progresszióval együtt).
- Blokktípusok: sets (exercise, sets, reps, opcionális weight_kg és rest_s), timed (exercise, duration_s, opcionális sets és rest_s), interval (name, work_s VAGY work_m, rest_s, rounds, opcionális target), segment (name, és duration_s VAGY distance_m, opcionális target), repeat (times, blocks).
- A reps lehet szám vagy szöveg (pl. "8-10", "max"). Idő másodpercben, táv méterben, súly kg-ban.
- Blokkok nélküli kardió edzésnél a target adja a célt: duration_min, distance_km, hr_zone, pace, note.

A kérésem: """
