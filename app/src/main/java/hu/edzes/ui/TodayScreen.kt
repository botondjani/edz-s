package hu.edzes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import hu.edzes.data.DONE
import hu.edzes.data.SKIPPED
import hu.edzes.data.Templates
import hu.edzes.model.intervalWorkout
import hu.edzes.model.Workout
import hu.edzes.model.sportEmoji
import hu.edzes.model.sportName
import hu.edzes.model.summary
import hu.edzes.sync.Hc
import hu.edzes.sync.Sync
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun TodayScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val (planned, activities) = rememberData()
    val start = rememberStarter()
    val today = LocalDate.now()
    val syncing by Sync.running.collectAsState()
    val syncStatus by Sync.status.collectAsState()
    var intervalDialog by remember { mutableStateOf(false) }
    var openActivity by remember { mutableStateOf<Long?>(null) }
    var templates by remember { mutableStateOf(Templates.load(ctx)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Title(fmtDay(today).replaceFirstChar { it.uppercase() })
                Text(
                    if (syncing) "Szinkronizálás…" else syncStatus.ifEmpty { "Még nem volt szinkron" }.let { "Szinkron: $it" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
            if (syncing) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
            else IconButton(onClick = { scope.launch { Sync.run(ctx.applicationContext) } }) { Icon(Icons.Default.Refresh, "Szinkronizálás") }
        }

        HealthConnectCard()

        val waiting = activities.filter { it.needsRoute }
        if (waiting.isNotEmpty()) Card(
            Modifier.fillMaxWidth().padding(top = 8.dp).clickable { openActivity = waiting.first().id },
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Text(
                "${waiting.size} edzés útvonalának feltöltéséhez engedély kell. Koppints ide, és válaszd a „Minden útvonal engedélyezése” lehetőséget.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }

        val week = planned.filter { it.status != SKIPPED && LocalDate.ofEpochDay(it.date).with(DayOfWeek.MONDAY) == today.with(DayOfWeek.MONDAY) }
        val todays = planned.filter { it.date == today.toEpochDay() }
        if (todays.isNotEmpty() || week.isNotEmpty())
            Section("Mai terv" + if (week.isNotEmpty()) " · a héten ${week.count { it.status == DONE }}/${week.size} kész" else "")
        todays.forEach { PlannedCard(it, start) }

        Section("Edzés indítása most")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartButton("run", Modifier.weight(1f)) { start(Workout(sportName("run"), "run"), null) }
            StartButton("bike", Modifier.weight(1f)) { start(Workout(sportName("bike"), "bike"), null) }
            StartButton("walk", Modifier.weight(1f)) { start(Workout(sportName("walk"), "walk"), null) }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StartButton("strength", Modifier.weight(1f), "Súlyzós") { start(Workout("Súlyzós edzés", "strength"), null) }
            StartButton("hiit", Modifier.weight(1f), "Intervall") { intervalDialog = true }
        }
        Text(
            "Futás, kerékpár, séta: élő idő, táv, tempó és pulzus, GPS-szel rögzítve. Súlyzós: sorozatok naplózása pihenőidővel. " +
                "Intervall: saját munka/pihenő körök. Az úszást az óra méri, utána magától megjelenik az Előzményekben.",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )

        if (templates.isNotEmpty()) Section("Mentett edzések")
        templates.forEach { t ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { start(t, null) }) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text("▶ ${sportEmoji(t.sport)} ${t.title}", style = MaterialTheme.typography.titleSmall)
                        Text(t.summary().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { templates = templates - t; Templates.save(ctx, templates) }) { Icon(Icons.Default.Close, "Törlés") }
                }
            }
        }

        val todaysActivities = activities.filter { dayOf(it.startMs) == today }
        if (todaysActivities.isNotEmpty()) Section("Ma elvégezve")
        todaysActivities.forEach { a -> ActivityRow(a) { openActivity = a.id } }

        val upcoming = planned.filter { it.date > today.toEpochDay() && it.date <= today.toEpochDay() + 7 }
        if (upcoming.isNotEmpty()) Section("Következő 7 nap")
        upcoming.forEach { PlannedCard(it, start, showDate = true) }
    }

    if (intervalDialog) IntervalDialog(onDismiss = { intervalDialog = false }) { workout, save ->
        if (save) { templates = templates + workout; Templates.save(ctx, templates) }
        start(workout, null)
    }
    activities.firstOrNull { it.id == openActivity }?.let { ActivityDialog(it) { openActivity = null } }
}

@Composable
private fun StartButton(sport: String, modifier: Modifier, label: String = sportName(sport), onClick: () -> Unit) =
    FilledTonalButton(onClick, modifier.height(64.dp)) { Text("${sportEmoji(sport)}\n$label", textAlign = TextAlign.Center) }

/** Amíg a Health Connect nincs összekapcsolva, a főképernyőn is szólunk, egy gombnyomásos beállítással. */
@Composable
private fun HealthConnectCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (!remember { Hc.available(ctx) }) return
    var tick by remember { mutableIntStateOf(0) }
    val granted by produceState(true, tick) { value = runCatching { Hc(ctx).granted() }.getOrDefault(false) }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        tick++
        scope.launch { Sync.run(ctx.applicationContext) }
    }
    if (!granted) Card(
        Modifier.fillMaxWidth().padding(top = 8.dp).clickable { request.launch(Hc.REQUEST) },
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Text(
            "A Health Connect nincs összekapcsolva, ezért az órával (Fitbit / Google Health) mért edzések és a pulzus nem jelennek meg. Koppints ide az engedélyek megadásához.",
            Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Intervall edzés összeállítása: bemelegítés, N × (munka idő vagy táv szerint + pihenő), levezetés. */
@Composable
private fun IntervalDialog(onDismiss: () -> Unit, onStart: (Workout, save: Boolean) -> Unit) {
    var sport by remember { mutableStateOf("run") }
    var name by remember { mutableStateOf("") }
    var warmup by remember { mutableStateOf("10") }
    var work by remember { mutableStateOf("400") }
    var byDistance by remember { mutableStateOf(true) }
    var rest by remember { mutableStateOf("90") }
    var rounds by remember { mutableStateOf("6") }
    var cooldown by remember { mutableStateOf("5") }
    var save by remember { mutableStateOf(false) }
    val gym = sport == "hiit"
    val w = work.toIntOrNull()?.takeIf { it > 0 }
    val r = rest.toIntOrNull()?.takeIf { it >= 0 }
    val n = rounds.toIntOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Intervall edzés") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("run" to "Futás", "bike" to "Kerékpár", "hiit" to "Terem").forEach { (s, label) ->
                        FilterChip(sport == s, onClick = { sport = s; if (s == "hiit") byDistance = false }, label = { Text(label) })
                    }
                }
                if (!gym) NumberField("Bemelegítés (perc)", warmup) { warmup = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(if (byDistance) "Munka (m)" else "Munka (mp)", work, Modifier.weight(1f)) { work = it }
                    if (!gym) {
                        FilterChip(byDistance, onClick = { byDistance = true }, label = { Text("táv") })
                        FilterChip(!byDistance, onClick = { byDistance = false }, label = { Text("idő") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Pihenő (mp)", rest, Modifier.weight(1f)) { rest = it }
                    NumberField("Körök", rounds, Modifier.weight(1f)) { rounds = it }
                }
                if (!gym) NumberField("Levezetés (perc)", cooldown) { cooldown = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(save, { save = it })
                    Text("Mentés a gyors indításhoz")
                }
                if (save) OutlinedTextField(name, { name = it }, label = { Text("Név (nem kötelező)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = w != null && r != null && n != null, onClick = {
                onDismiss()
                onStart(intervalWorkout(sport, name, warmup.toIntOrNull() ?: 0, w!!, byDistance, r!!, n!!, cooldown.toIntOrNull() ?: 0), save)
            }) { Text("Indítás") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, decimal: Boolean = false, onChange: (String) -> Unit) =
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
