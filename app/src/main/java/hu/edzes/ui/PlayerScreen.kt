package hu.edzes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.edzes.dao
import hu.edzes.data.SetLog
import hu.edzes.model.Block
import hu.edzes.model.Step
import hu.edzes.model.flatten
import hu.edzes.model.fmtKg
import hu.edzes.model.fmtMeters
import hu.edzes.model.isCardio
import hu.edzes.player.Player
import hu.edzes.player.PlayerState

private fun num(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
private fun String.toKg() = replace(',', '.').toDoubleOrNull()

@Composable
fun PlayerScreen(state: PlayerState) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var finishing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    // Az edzés közben beírt utolsó értékek gyakorlatonként: a következő sorozat ezekkel indul.
    val entered = remember { mutableStateMapOf<String, Pair<String, String>>() }
    BackHandler { finishing = true }
    val step = state.step
    val cardio = isCardio(state.workout.sport)
    // Intervallnál ránézésre látszódjon, hogy munka vagy pihenő van.
    val background = when {
        step == null || step.manual -> MaterialTheme.colorScheme.surface
        step.rest -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    Surface(Modifier.fillMaxSize(), color = background) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.workout.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(fmtClock(state.totalMs / 1000), style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(
                progress = { if (state.stepCount == 0) 0f else state.index.toFloat() / state.stepCount },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            state.remainingS?.let {
                Text("Hátra összesen: ${fmtClock(it)}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (cardio || state.heartRate != null) Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                if (cardio) {
                    Metric("Táv (km)", if (state.gps) "%.2f".format(HU, state.totalDistM / 1000) else "–", Modifier.weight(1f))
                    Metric(if (state.workout.sport == "bike") "km/h" else "Tempó (/km)", fmtPace(state.paceSPerKm, state.workout.sport), Modifier.weight(1f))
                }
                // Célzónán kívül a pulzus pirossal jelenik meg.
                val off = state.zone != null && state.targetZone != null && state.zone != state.targetZone
                Metric(
                    "Pulzus" + (state.zone?.let { " · Z$it" } ?: "") + (state.targetZone?.let { " (cél Z$it)" } ?: ""),
                    state.heartRate?.toString() ?: "–", Modifier.weight(1f), if (off) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
            if (cardio && !state.gps) Text(
                "Nincs GPS (engedély vagy helymeghatározás kikapcsolva): táv és tempó nem mérhető.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.weight(1f))

            if (step == null && cardio) {
                // Szabad futás/kerékpár, vagy a szakaszok elfogytak: a fő szám az eltelt idő.
                BigNumber(fmtClock(state.totalMs / 1000))
                Text(if (state.stepCount == 0) state.workout.target ?: "Szabad edzés" else "Minden szakasz kész 🎉", style = MaterialTheme.typography.titleMedium)
            } else if (step == null) {
                Text(
                    if (state.stepCount == 0) "Szabad edzés" else "Minden lépés kész 🎉",
                    style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                )
                Text("Adj hozzá gyakorlatot, vagy fejezd be az edzést.", Modifier.padding(top = 8.dp))
            } else {
                Text(
                    step.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                if (step.info.isNotEmpty()) Text(step.info, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                when {
                    step.durationS != null -> BigNumber(fmtClock((step.durationS * 1000L - state.stepMs + 999) / 1000))
                    step.distanceM != null -> {
                        BigNumber(fmtMeters((step.distanceM - state.stepDistM).toInt().coerceAtLeast(0)))
                        Text(
                            if (state.gps) "hátra · eltelt ${fmtClock(state.stepMs / 1000)}" else "A szakasz végén koppints a Tovább gombra",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> SetInput(state.index, step, entered)
                }
                state.next?.let {
                    Text("Következő: ${it.title} ${it.info}", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Player.prev() }, enabled = state.index > 0) { Text("◀ Vissza") }
                FilledTonalButton(onClick = { Player.togglePause() }) { Text(if (state.paused) "▶ Folytatás" else "⏸ Szünet") }
                OutlinedButton(onClick = { Player.next() }, enabled = step != null) { Text("Tovább ▶") }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isCardio(state.workout.sport)) TextButton(onClick = { adding = true }) { Text("+ Gyakorlat") }
                TextButton(onClick = { finishing = true }) { Text("Befejezés") }
            }
        }
    }

    if (adding) AddExerciseDialog { adding = false }
    if (finishing) AlertDialog(
        onDismissRequest = { finishing = false },
        title = { Text("Edzés befejezése") },
        text = { Text("Eltelt idő: ${fmtClock(state.totalMs / 1000)}. Mented az edzést?") },
        confirmButton = { TextButton(onClick = { Player.finish(save = true) }) { Text("Mentés") } },
        dismissButton = {
            Row {
                TextButton(onClick = { Player.finish(save = false) }) { Text("Elvetés") }
                TextButton(onClick = { finishing = false }) { Text("Folytatom") }
            }
        },
    )
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, color: Color = Color.Unspecified) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, fontSize = 40.sp, fontWeight = FontWeight.Medium, color = color)
    Text(label, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun BigNumber(text: String) =
    Text(text, fontSize = 88.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(vertical = 8.dp))

/** Súlyzós sorozat: ismétlés és súly beírása, előtöltve (idei edzés > terv > legutóbbi alkalom). */
@Composable
private fun SetInput(index: Int, step: Step, entered: MutableMap<String, Pair<String, String>>) {
    val dao = LocalContext.current.dao
    val exercise = step.exercise ?: return
    var reps by remember(index) { mutableStateOf(entered[exercise]?.first ?: step.reps?.toIntOrNull()?.toString() ?: "") }
    var weight by remember(index) { mutableStateOf(entered[exercise]?.second ?: step.weightKg?.let(::num) ?: "") }
    var last by remember(exercise) { mutableStateOf<SetLog?>(null) }
    LaunchedEffect(index) {
        last = dao.lastSet(exercise)
        if (reps.isEmpty()) reps = last?.reps?.toString().orEmpty()
        if (weight.isEmpty()) weight = last?.weightKg?.let(::num).orEmpty()
    }
    last?.let {
        Text("Legutóbb: ${it.reps} ism." + (it.weightKg?.let { kg -> " × ${fmtKg(kg)}" } ?: ""), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    Stepper("Ismétlés", reps, 1.0, false) { reps = it }
    Stepper("Súly (kg)", weight, 2.5, true) { weight = it }
    Button(
        onClick = { entered[exercise] = reps to weight; Player.completeSet(reps.toIntOrNull(), weight.toKg()) },
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth().height(64.dp),
    ) { Text("Sorozat kész", style = MaterialTheme.typography.titleLarge) }
}

@Composable
private fun Stepper(label: String, value: String, step: Double, decimal: Boolean, onChange: (String) -> Unit) {
    fun bump(by: Double) = onChange(num(((value.toKg() ?: 0.0) + by).coerceAtLeast(0.0)))
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { bump(-step) }) { Text("−") }
        OutlinedTextField(
            value, onChange, Modifier.width(140.dp), label = { Text(label) }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        )
        FilledTonalButton(onClick = { bump(step) }) { Text("+") }
    }
}

@Composable
private fun AddExerciseDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf("3") }
    var reps by remember { mutableStateOf("10") }
    var weight by remember { mutableStateOf("") }
    var rest by remember { mutableStateOf("90") }
    val setCount = sets.toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gyakorlat hozzáadása") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Gyakorlat") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Sorozat", sets, Modifier.weight(1f)) { sets = it }
                    NumberField("Ismétlés", reps, Modifier.weight(1f)) { reps = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Súly (kg)", weight, Modifier.weight(1f), decimal = true) { weight = it }
                    NumberField("Pihenő (mp)", rest, Modifier.weight(1f)) { rest = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && setCount != null && reps.isNotBlank(), onClick = {
                Player.append(flatten(listOf(Block("sets", name.trim(), setCount!!, reps, weight.toKg(), rest.toIntOrNull() ?: 0))))
                onDismiss()
            }) { Text("Hozzáadás") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
