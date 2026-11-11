package hu.edzes.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import hu.edzes.UiState
import hu.edzes.dao
import hu.edzes.data.Activity
import hu.edzes.model.sportEmoji
import hu.edzes.sync.Strava
import hu.edzes.sync.Sync
import hu.edzes.sync.describe
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Title("Előzmények") }
        if (activities.isEmpty()) item {
            Text("Még nincs edzés. A konditermi edzéseket az app méri, a többit a Health Connectből és a Stravából veszi át.")
        }
        items(activities, key = { it.id }) { a -> ActivityRow(a) { open = a.id } }
    }
    activities.firstOrNull { it.id == open }?.let { ActivityDialog(it) { open = null } }
}

private fun Activity.stats() = listOfNotNull(
    fmtClock(durationS.toLong()),
    distanceM?.let(::fmtKm),
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

@Composable
fun ActivityDialog(a: Activity, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sets by remember(a.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val stravaConnected = remember(UiState.stravaTick) { Strava(ctx).connected }
    LaunchedEffect(a.id) { sets = describe(ctx.dao.setLogs(a.id)) }

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
        upload({ Sync.upload(ctx.applicationContext, a, route, routeDenied = route == null) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${sportEmoji(a.sport)} ${a.title}") },
        text = {
            Column {
                Text(Instant.ofEpochMilli(a.startMs).atZone(ZoneId.systemDefault()).format(TIME_FORMAT))
                Text(a.stats())
                Text(
                    "Forrás: " + when (a.source) { "app" -> "ez az app"; "hc" -> "Health Connect"; else -> "Strava" },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (sets.isNotEmpty()) Text(sets, Modifier.padding(top = 8.dp))
                a.stravaError?.let { Text("Strava hiba: $it", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                if (a.source != "app") Text(
                    "Törléskor csak ebből az appból tűnik el; a Stravában és a Health Connectben megmarad.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            when {
                a.stravaId != null -> TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/activities/${a.stravaId}")))
                }) { Text("Megnyitás Stravában") }
                stravaConnected -> TextButton(enabled = !busy, onClick = {
                    if (a.needsRoute) routeConsent.launch(a.hcId!!)
                    else upload({ Sync.upload(ctx.applicationContext, a.copy(stravaError = null)) }) {
                        if (ctx.dao.activity(a.id)?.needsRoute == true) routeConsent.launch(a.hcId!!)
                    }
                }) { Text(if (busy) "Feltöltés…" else "Feltöltés Stravára") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDismiss()
                    scope.launch {
                        if (a.source == "app") ctx.dao.deleteActivity(a.id)
                        else { ctx.dao.unlinkActivity(a.id); ctx.dao.update(a.copy(hidden = true)) }
                    }
                }) { Text("Törlés") }
                TextButton(onClick = onDismiss) { Text("Bezárás") }
            }
        },
    )
}
