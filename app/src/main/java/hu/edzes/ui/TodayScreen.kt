package hu.edzes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import hu.edzes.data.DONE
import hu.edzes.data.SKIPPED
import hu.edzes.model.Block
import hu.edzes.model.Workout
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
        Section("Mai edzések" + if (week.isNotEmpty()) " · a héten ${week.count { it.status == DONE }}/${week.size} kész" else "")
        val todays = planned.filter { it.date == today.toEpochDay() }
        if (todays.isEmpty()) Text("Mára nincs tervezett edzés.", style = MaterialTheme.typography.bodyMedium)
        todays.forEach { PlannedCard(it, start) }
        activities.filter { dayOf(it.startMs) == today }.forEach { a -> ActivityRow(a) { openActivity = a.id } }

        Section("Gyors indítás")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { start(Workout("Szabad edzés", "strength"), null) }) { Text("🏋️ Szabad edzés") }
            FilledTonalButton(onClick = { intervalDialog = true }) { Text("⏱️ Intervall") }
        }

        val upcoming = planned.filter { it.date > today.toEpochDay() && it.date <= today.toEpochDay() + 7 }
        if (upcoming.isNotEmpty()) Section("Következő 7 nap")
        upcoming.forEach { PlannedCard(it, start, showDate = true) }
    }

    if (intervalDialog) IntervalDialog(onDismiss = { intervalDialog = false }) { start(it, null) }
    activities.firstOrNull { it.id == openActivity }?.let { ActivityDialog(it) { openActivity = null } }
}

@Composable
private fun IntervalDialog(onDismiss: () -> Unit, onStart: (Workout) -> Unit) {
    var work by remember { mutableStateOf("20") }
    var rest by remember { mutableStateOf("10") }
    var rounds by remember { mutableStateOf("8") }
    val w = work.toIntOrNull()?.takeIf { it > 0 }
    val r = rest.toIntOrNull()?.takeIf { it >= 0 }
    val n = rounds.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Intervall időzítő") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Munka (mp)", work) { work = it }
                NumberField("Pihenő (mp)", rest) { rest = it }
                NumberField("Körök", rounds) { rounds = it }
            }
        },
        confirmButton = {
            TextButton(enabled = w != null && r != null && n != null, onClick = {
                onDismiss()
                onStart(Workout("Intervall", "hiit", blocks = listOf(Block("interval", "Intervall", sets = n!!, durationS = w, restS = r!!))))
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
