package hu.edzes.data

import android.content.Context
import androidx.core.content.edit
import hu.edzes.model.Workout
import hu.edzes.prefs
import hu.edzes.sync.TrackPoint
import kotlinx.serialization.json.Json
import java.io.File

/** Elmentett, bármikor indítható edzések (pl. intervall sablonok). */
object Templates {
    fun load(ctx: Context): List<Workout> =
        runCatching { Json.decodeFromString<List<Workout>>(ctx.prefs.getString("templates", "[]")!!) }.getOrDefault(emptyList())

    fun save(ctx: Context, list: List<Workout>) = ctx.prefs.edit { putString("templates", Json.encodeToString(list)) }
}

/** Az appban rögzített GPS útvonalak: edzésenként egy CSV (idő, szélesség, hosszúság, magasság, pulzus). */
object Tracks {
    private fun file(ctx: Context, activityId: Long) = File(ctx.filesDir, "tracks/$activityId.csv")

    fun write(ctx: Context, activityId: Long, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        val f = file(ctx, activityId)
        f.parentFile!!.mkdirs()
        f.writeText(points.joinToString("\n") { "${it.timeMs},${it.lat ?: ""},${it.lon ?: ""},${it.altM ?: ""},${it.hr ?: ""}" })
    }

    fun read(ctx: Context, activityId: Long): List<TrackPoint> {
        val f = file(ctx, activityId)
        if (!f.exists()) return emptyList()
        return f.readLines().map { line ->
            val p = line.split(',')
            TrackPoint(p[0].toLong(), p[1].toDoubleOrNull(), p[2].toDoubleOrNull(), p[3].toDoubleOrNull(), p[4].toIntOrNull())
        }
    }

    fun delete(ctx: Context, activityId: Long) { file(ctx, activityId).delete() }
}
