package hu.edzes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRouteResult
import hu.edzes.UiState
import hu.edzes.dao
import hu.edzes.data.Activity
import hu.edzes.data.SetLog
import hu.edzes.data.Tracks
import hu.edzes.model.describe
import hu.edzes.model.fmtMeters
import hu.edzes.model.paceSeries
import hu.edzes.model.sportEmoji
import hu.edzes.model.zoneSeconds
import hu.edzes.prefs
import hu.edzes.sync.Hc
import hu.edzes.sync.Strava
import hu.edzes.sync.Sync
import hu.edzes.sync.TrackPoint
import hu.edzes.sync.mergePoints
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy. MMM d. HH:mm", HU)

@Composable
fun HistoryScreen() {
    val (_, activities) = rememberData()
    var open by remember { mutableStateOf<Long?>(null) }
    var stats by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Title("Előzmények")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!stats, onClick = { stats = false }, label = { Text("Edzések") })
                FilterChip(stats, onClick = { stats = true }, label = { Text("Statisztika") })
            }
        }
        if (stats) item { Column { StatsContent(activities) } }
        else {
            if (activities.isEmpty()) item {
                Text("Még nincs edzés. Indíts egyet a Ma fülről; az órával mért edzések a Health Connectből, a többi a Stravából érkezik.")
            }
            items(activities, key = { it.id }) { a -> ActivityRow(a) { open = a.id } }
        }
    }
    activities.firstOrNull { it.id == open }?.let { ActivityDialog(it) { open = null } }
}

private fun Activity.stats() = listOfNotNull(
    fmtClock(durationS.toLong()),
    distanceM?.let(::fmtKm),
    distanceM?.takeIf { it > 0 }?.let { fmtPace((durationS / (it / 1000)).toInt(), sport) + if (sport == "bike") " km/h" else " /km" },
    avgHr?.let { "♥ $it" },
    calories?.let { "$it kcal" },
).joinToString(" · ")

@Composable
fun ActivityRow(a: Activity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(sportEmoji(a.sport), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, fontWeight = FontWeight.SemiBold)
            Text(
                Instant.ofEpochMilli(a.startMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT) + " · " + a.stats(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            when {
                a.stravaId != null -> "Strava ✓"
                a.stravaError != null -> "Strava ⚠"
                a.needsRoute -> "Útvonal?"
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Az edzés GPS- és pulzuspontjai: az appban rögzített útvonal, vagy a Health Connect adatai (ha már engedélyezett az útvonal). */
private suspend fun loadPoints(ctx: Context, a: Activity): List<TrackPoint> = runCatching {
    Tracks.read(ctx, a.id).ifEmpty {
        if (!Hc.available(ctx)) return@ifEmpty emptyList()
        val hc = Hc(ctx)
        if (!hc.granted()) return@ifEmpty emptyList()
        val route = a.hcId?.let { (hc.route(it) as? ExerciseRouteResult.Data)?.exerciseRoute?.route }.orEmpty()
            .map { TrackPoint(it.time.toEpochMilli(), it.latitude, it.longitude, it.altitude?.inMeters) }
        mergePoints(route, hc.heartRates(a.startMs, a.startMs + a.durationS * 1000L))
    }
}.getOrDefault(emptyList())

/** Az edzés részletei teljes képernyőn: adatok, útvonal, tempó- és pulzusgörbe, zónaidők, részidők / sorozatok. */
@Composable
fun ActivityDialog(a: Activity, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember(a.id) { mutableStateOf(emptyList<SetLog>()) }
    var points by remember(a.id) { mutableStateOf(emptyList<TrackPoint>()) }
    var busy by remember { mutableStateOf(false) }
    val stravaConnected = remember(UiState.stravaTick) { Strava(ctx).connected }
    val maxHr = ctx.prefs.getInt("max_hr", 0).takeIf { it > 0 }
    LaunchedEffect(a.id) {
        logs = ctx.dao.setLogs(a.id)
        points = loadPoints(ctx, a)
    }

    fun upload(block: suspend () -> Boolean, onFail: suspend () -> Unit = {}) = scope.launch {
        busy = true
        try {
            if (block()) ctx.toast("Feltöltve a Stravára") else onFail()
        } catch (e: IOException) {
            ctx.toast("Nincs internetkapcsolat")
        } finally {
            busy = false
        }
    }
    // Az útvonalhoz a Health Connect saját engedélykérő ablaka kell; a válasz után töltünk fel.
    val routeConsent = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        upload({ Sync.upload(ctx.applicationContext, a, route, routeDenied = route == null, force = true) })
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Title("${sportEmoji(a.sport)} ${a.title}")
                Text(Instant.ofEpochMilli(a.startMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT))
                Text(a.stats(), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Forrás: " + when (a.source) { "app" -> "ez az app"; "hc" -> "Health Connect"; else -> "Strava" },
                    style = MaterialTheme.typography.bodySmall,
                )

                val gps = points.filter { it.lat != null && it.lon != null }
                if (gps.size >= 2) {
                    Section("Útvonal")
                    RouteMap(gps.map { it.lat!! to it.lon!! })
                    val pace = paceSeries(gps.map { Triple(it.timeMs, it.lat!!, it.lon!!) }).filter { it < 1200 }   // 20 perc/km fölött állásnak számít
                    if (pace.size >= 2) {
                        Section(if (a.sport == "bike") "Sebesség" else "Tempó")
                        LineChart(listOf(MaterialTheme.colorScheme.primary to pace), invert = true)
                        Text(
                            "leggyorsabb ${fmtPace(pace.min().toInt(), a.sport)} · leglassabb ${fmtPace(pace.max().toInt(), a.sport)}" +
                                if (a.sport == "bike") " km/h" else " /km",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val hr = points.mapNotNull { it.hr?.toFloat() }
                if (hr.size >= 2) {
                    Section("Pulzus")
                    LineChart(listOf(MaterialTheme.colorScheme.error to hr))
                    Text("legalacsonyabb ${hr.min().toInt()} · legmagasabb ${hr.max().toInt()}", style = MaterialTheme.typography.bodySmall)
                }
                maxHr?.let { zoneSeconds(a.hrHist, it) }?.let { z ->
                    Section("Idő a zónákban")
                    StackedBars((1..5).map { zone -> List(5) { if (it == zone - 1) z[zone].toFloat() else 0f } }, ZONE_COLORS.drop(1))
                    Text((1..5).joinToString(" · ") { "Z$it ${fmtClock(z[it].toLong())}" }, style = MaterialTheme.typography.bodySmall)
                }

                val laps = logs.filter { it.distanceM != null && it.durationS != null }
                if (laps.isNotEmpty()) {
                    Section("Részidők")
                    laps.forEach { lap ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(lap.exercise, Modifier.weight(2f))
                            Text(fmtMeters(lap.distanceM!!.toInt()), Modifier.weight(1f))
                            Text(fmtClock(lap.durationS!!.toLong()), Modifier.weight(1f))
                            Text(
                                if (lap.distanceM > 0) fmtPace((lap.durationS / (lap.distanceM / 1000)).toInt(), a.sport) else "–",
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
                val sets = logs - laps.toSet()
                if (sets.isNotEmpty()) {
                    Section("Sorozatok")
                    Text(describe(sets))
                }

                a.stravaError?.let { Text("Strava hiba: $it", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                if (a.source != "app") Text(
                    "Törléskor csak ebből az appból tűnik el; a Stravában és a Health Connectben megmarad.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.padding(top = 16.dp)) {
                    when {
                        a.stravaId != null -> TextButton(onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/${a.stravaId}")))
                        }) { Text("Megnyitás Stravában") }
                        stravaConnected -> TextButton(enabled = !busy, onClick = {
                            if (a.needsRoute) routeConsent.launch(a.hcId!!)
                            else upload({ Sync.upload(ctx.applicationContext, a.copy(stravaError = null), force = true) }) {
                                if (ctx.dao.activity(a.id)?.needsRoute == true) routeConsent.launch(a.hcId!!)
                            }
                        }) { Text(if (busy) "Feltöltés…" else "Feltöltés Stravára") }
                    }
                    TextButton(onClick = {
                        onDismiss()
                        scope.launch {
                            if (a.source == "app") { ctx.dao.deleteActivity(a.id); Tracks.delete(ctx, a.id) }
                            else { ctx.dao.unlinkActivity(a.id); ctx.dao.update(a.copy(hidden = true)) }
                        }
                    }) { Text("Törlés") }
                    TextButton(onClick = onDismiss) { Text("Bezárás") }
                }
            }
        }
    }
}
