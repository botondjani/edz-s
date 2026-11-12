package hu.edzes.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import hu.edzes.dao
import hu.edzes.data.Activity
import hu.edzes.data.DONE
import hu.edzes.data.PLANNED
import hu.edzes.data.Planned
import hu.edzes.data.SKIPPED
import hu.edzes.model.Workout
import hu.edzes.model.isCardio
import hu.edzes.model.sportEmoji
import hu.edzes.player.HeartRateMonitor
import hu.edzes.prefs
import hu.edzes.model.steps
import hu.edzes.model.summary
import hu.edzes.player.Player
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val HU: Locale = Locale.forLanguageTag("hu-HU")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d., EEEE", HU)

fun fmtDay(date: LocalDate): String = date.format(DAY_FORMAT)
fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** 754 -> "12:34", 3754 -> "1:02:34" */
fun fmtClock(totalS: Long): String {
    val h = totalS / 3600
    val m = totalS % 3600 / 60
    val s = totalS % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtKm(m: Double): String = "%.2f km".format(HU, m / 1000)

/** Tempó (mp/km) a sportnak megfelelően: futásnál perc/km, kerékpárnál km/h. */
fun fmtPace(sPerKm: Int?, sport: String): String = when {
    sPerKm == null || sPerKm <= 0 -> "–"
    sport == "bike" -> "%.1f".format(HU, 3600.0 / sPerKm)
    else -> fmtClock(sPerKm.toLong())
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

fun Planned.workout(): Workout = Json.decodeFromString(workoutJson)

@Composable
fun rememberData(): Pair<List<Planned>, List<Activity>> {
    val dao = LocalContext.current.dao
    val planned by remember { dao.planned() }.collectAsState(emptyList())
    val activities by remember { dao.activities() }.collectAsState(emptyList())
    return planned to activities.filter { !it.hidden }
}

/** Edzésindító, ami előbb elkéri a szükséges engedélyeket (értesítés; kardióhoz GPS; élő pulzushoz Bluetooth). */
@Composable
fun rememberStarter(): (Workout, Long?) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Pair<Workout, Long?>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let { (w, id) -> Player.start(ctx, w, id) }
        pending = null
    }
    return { workout, plannedId ->
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (isCardio(workout.sport) || workout.steps().any { it.distanceM != null }) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (ctx.prefs.getBoolean("live_hr", true)) addAll(HeartRateMonitor.PERMISSIONS)
        }.distinct().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) Player.start(ctx, workout, plannedId)
        else { pending = workout to plannedId; launcher.launch(missing.toTypedArray()) }
    }
}

@Composable
fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))

@Composable
fun Section(text: String) = Text(
    text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
)

@Composable
fun PlannedCard(p: Planned, onStart: (Workout, Long?) -> Unit, showDate: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val workout = remember(p.workoutJson) { p.workout() }
    var menu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    fun save(new: Planned) { scope.launch { ctx.dao.update(new) } }

    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (showDate) Text(fmtDay(LocalDate.ofEpochDay(p.date)), style = MaterialTheme.typography.labelMedium)
                    Text("${sportEmoji(p.sport)} ${p.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    when (p.status) { DONE -> "✓ Kész"; SKIPPED -> "Kihagyva"; else -> "" },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (p.status == DONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Műveletek") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem({ Text("Áthelyezés") }, onClick = { menu = false; moving = true })
                        if (p.status != PLANNED)
                            DropdownMenuItem({ Text("Visszaállítás") }, onClick = { menu = false; save(p.copy(status = PLANNED, activityId = null)) })
                        DropdownMenuItem({ Text("Törlés") }, onClick = { menu = false; scope.launch { ctx.dao.deletePlanned(p.id) } })
                    }
                }
            }
            workout.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            workout.summary().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (p.status == PLANNED) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Úszást a telefon nem tud mérni: azt az óra rögzíti, és a Health Connectből érkezik.
                if (p.sport != "swim") TextButton(onClick = { onStart(workout, p.id) }) { Text("▶ Indítás") }
                TextButton(onClick = { save(p.copy(status = DONE)) }) { Text("Kész") }
                TextButton(onClick = { save(p.copy(status = SKIPPED)) }) { Text("Kihagyom") }
            }
        }
    }
    if (moving) DatePick(LocalDate.ofEpochDay(p.date), onDismiss = { moving = false }) { save(p.copy(date = it.toEpochDay())) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePick(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    ) { DatePicker(state) }
}
