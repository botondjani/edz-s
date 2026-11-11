package hu.edzes.sync

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import hu.edzes.data.Activity
import hu.edzes.model.sportOfStrava
import hu.edzes.model.stravaType
import hu.edzes.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class StravaActivity(
    val id: Long, val name: String, val sport: String, val startMs: Long,
    val durationS: Int, val distanceM: Double?, val avgHr: Int?,
)

/** A Strava visszautasította a kérést (nem hálózati hiba). */
class StravaException(message: String) : Exception(message)

class Strava(ctx: Context) {
    private val prefs = ctx.prefs
    private val http = OkHttpClient()

    val connected get() = prefs.getString("strava_refresh", null) != null
    val connectedAtMs get() = prefs.getLong("strava_connected_at", Long.MAX_VALUE)

    fun authUri(): Uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
        .appendQueryParameter("client_id", prefs.getString("strava_client_id", ""))
        .appendQueryParameter("redirect_uri", REDIRECT)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("approval_prompt", "auto")
        .appendQueryParameter("scope", "activity:write,activity:read_all")
        .build()

    suspend fun exchange(code: String) {
        saveTokens(tokenCall("grant_type" to "authorization_code", "code" to code))
        prefs.edit { putLong("strava_connected_at", System.currentTimeMillis()) }
    }

    fun disconnect() = prefs.edit {
        remove("strava_access"); remove("strava_refresh"); remove("strava_expires"); remove("strava_connected_at")
    }

    suspend fun activities(afterS: Long): List<StravaActivity> {
        val out = mutableListOf<StravaActivity>()
        var page = 1
        do {
            val items = call(get("athlete/activities?after=$afterS&per_page=100&page=${page++}")).jsonArray
            out += items.map { parseActivity(it.jsonObject) }
        } while (items.size == 100)
        return out
    }

    suspend fun createManual(a: Activity, description: String): Long {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(a.startMs), ZoneId.systemDefault()).withNano(0)
        val form = FormBody.Builder()
            .add("name", a.title).add("sport_type", stravaType(a.sport))
            .add("start_date_local", "${local}Z").add("elapsed_time", a.durationS.toString())
            .add("description", description)
        a.distanceM?.let { form.add("distance", it.toString()) }
        return call(post("activities", form.build())).jsonObject.long("id")!!
    }

    /** Feltölti a TCX-et, megvárja a feldolgozást, majd beállítja a nevet, sportot és leírást. */
    suspend fun uploadTcx(tcx: String, a: Activity, description: String): Long {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("data_type", "tcx").addFormDataPart("name", a.title)
            .addFormDataPart("external_id", "edzes-${a.id}-${a.startMs}")
            .addFormDataPart("file", "edzes.tcx", tcx.toRequestBody("application/xml".toMediaType()))
            .build()
        val id = try {
            var status = call(post("uploads", body)).jsonObject
            var tries = 0
            while (status.long("activity_id") == null) {
                status.str("error")?.let { throw StravaException(it) }
                if (tries++ >= 30) throw StravaException("A Strava nem dolgozta fel időben a feltöltést")
                delay(2000)
                status = call(get("uploads/${status.long("id")}")).jsonObject
            }
            status.long("activity_id")!!
        } catch (e: StravaException) {
            // Ha a Strava szerint már fent van, azt az edzést kapcsoljuk hozzá.
            duplicateId(e.message.orEmpty()) ?: throw e
        }
        val form = FormBody.Builder().add("name", a.title).add("sport_type", stravaType(a.sport)).add("description", description)
        call(authed("activities/$id").put(form.build()).build())
        return id
    }

    private fun parseActivity(o: JsonObject) = StravaActivity(
        id = o.long("id")!!,
        name = o.str("name").orEmpty(),
        sport = sportOfStrava(o.str("sport_type") ?: o.str("type").orEmpty()),
        startMs = Instant.parse(o.str("start_date")).toEpochMilli(),
        durationS = (o.long("elapsed_time") ?: 0).toInt(),
        distanceM = o["distance"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 },
        avgHr = o["average_heartrate"]?.jsonPrimitive?.doubleOrNull?.toInt(),
    )

    private suspend fun token(): String {
        if (prefs.getLong("strava_expires", 0) < System.currentTimeMillis() / 1000 + 60)
            saveTokens(tokenCall("grant_type" to "refresh_token", "refresh_token" to prefs.getString("strava_refresh", "")!!))
        return prefs.getString("strava_access", "")!!
    }

    private suspend fun tokenCall(vararg params: Pair<String, String>): JsonObject {
        val form = FormBody.Builder()
            .add("client_id", prefs.getString("strava_client_id", "")!!)
            .add("client_secret", prefs.getString("strava_client_secret", "")!!)
        params.forEach { form.add(it.first, it.second) }
        return call(Request.Builder().url("https://www.strava.com/oauth/token").post(form.build()).build()).jsonObject
    }

    private fun saveTokens(o: JsonObject) = prefs.edit {
        putString("strava_access", o.str("access_token"))
        putString("strava_refresh", o.str("refresh_token"))
        putLong("strava_expires", o.long("expires_at") ?: 0)
    }

    private suspend fun authed(path: String) = Request.Builder().url(API + path).header("Authorization", "Bearer ${token()}")
    private suspend fun get(path: String) = authed(path).build()
    private suspend fun post(path: String, body: RequestBody) = authed(path).post(body).build()

    private suspend fun call(req: Request): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw StravaException("Strava ${r.code}: ${body.take(300)}")
            Json.parseToJsonElement(body)
        }
    }

    companion object {
        const val REDIRECT = "edzes://localhost/strava"
        private const val API = "https://www.strava.com/api/v3/"

        fun duplicateId(error: String): Long? =
            if ("duplicate" in error) Regex("activities/(\\d+)").find(error)?.groupValues?.get(1)?.toLong() else null
    }
}

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
