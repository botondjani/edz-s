package hu.edzes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.edzes.UiState
import hu.edzes.dao
import hu.edzes.data.Planned
import hu.edzes.data.Program
import hu.edzes.model.CLAUDE_PROMPT
import hu.edzes.model.ProgramParser
import hu.edzes.model.ProgramSpec
import hu.edzes.model.dates
import hu.edzes.model.sportEmoji
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** A program edzéseit beütemezi a kiválasztott nap hetének hétfőjétől. */
suspend fun schedule(ctx: Context, programId: Long, spec: ProgramSpec, picked: LocalDate) {
    val monday = picked.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    ctx.dao.insertPlanned(spec.dates(monday).map { (date, w) ->
        Planned(programId = programId, date = date.toEpochDay(), title = w.title, sport = w.sport, workoutJson = Json.encodeToString(w))
    })
    ctx.toast("Beütemezve ${fmtDay(monday)} kezdettel")
}

private fun nextMonday(): LocalDate = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))

@Composable
fun ProgramsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val programs by remember { ctx.dao.programs() }.collectAsState(emptyList())
    var scheduling by remember { mutableStateOf<Program?>(null) }
    var deleting by remember { mutableStateOf<Program?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Title("Programok")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { UiState.importText = "" }) { Text("Importálás") }
            FilledTonalButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Edzés prompt", CLAUDE_PROMPT))
                ctx.toast("Prompt a vágólapon – illeszd be Claude-ba, és írd a végére, mit szeretnél")
            }) { Text("Claude prompt másolása") }
        }
        if (programs.isEmpty()) Text(
            "Még nincs program. Másold ki a promptot, kérj Claude-tól edzéstervet, majd a kapott JSON-t importáld ide " +
                "(beillesztéssel, fájlból, vagy a Claude appból megosztva).",
            Modifier.padding(top = 16.dp),
        )
        programs.forEach { p ->
            val spec = remember(p.id) { ProgramParser.parse(p.json).program }
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${p.weeks} hét · ${spec?.days?.size ?: 0} edzés", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { scheduling = p }) { Text("Ütemezés / újraindítás") }
                        TextButton(onClick = { deleting = p }) { Text("Törlés") }
                    }
                }
            }
        }
    }

    scheduling?.let { p ->
        DatePick(nextMonday(), onDismiss = { scheduling = null }) { date ->
            ProgramParser.parse(p.json).program?.let { scope.launch { schedule(ctx, p.id, it, date) } }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Törlöd a programot?") },
            text = { Text("„${p.name}” és a még el nem végzett, beütemezett edzései törlődnek. Az elvégzett edzések megmaradnak.") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch { ctx.dao.deleteProgram(p.id) } }) { Text("Törlés") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Mégse") } },
        )
    }
}

@Composable
fun ImportScreen(initial: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var saved by remember { mutableStateOf<Pair<Long, ProgramSpec>?>(null) }
    val result = remember(text) { ProgramParser.parse(text) }
    val close = { UiState.importText = null }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.reader().readText() } }.getOrDefault("")
    }
    BackHandler(onBack = close)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Title("Program importálása")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    text = ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                }) { Text("Beillesztés") }
                FilledTonalButton(onClick = { openFile.launch(arrayOf("application/json", "text/*")) }) { Text("Fájl megnyitása") }
            }
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 120.dp, max = 240.dp),
                label = { Text("Claude válasza (JSON)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            val program = result.program
            if (text.isNotBlank() && program == null) {
                Section("Hibák")
                result.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (program != null) {
                Section("Előnézet")
                Text(program.name, style = MaterialTheme.typography.titleMedium)
                Text("${program.weeks} hét · ${program.days.size} edzés", style = MaterialTheme.typography.bodySmall)
                program.days.filter { it.week == 1 }.forEach {
                    Text("1. hét, ${WEEKDAYS[it.weekday - 1]}: ${sportEmoji(it.workout.sport)} ${it.workout.title}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = program != null, onClick = {
                    scope.launch {
                        val id = ctx.dao.insert(Program(name = program!!.name, json = text, weeks = program.weeks, createdMs = System.currentTimeMillis()))
                        saved = id to program
                    }
                }) { Text("Mentés és ütemezés") }
                TextButton(onClick = close) { Text("Mégse") }
            }
        }
    }
    // Mentés után rögtön kezdőhetet választunk; ha a felhasználó kilép, a program ütemezés nélkül marad meg.
    saved?.let { (id, spec) ->
        DatePick(nextMonday(), onDismiss = close) { date -> scope.launch { schedule(ctx, id, spec, date) } }
    }
}

private val WEEKDAYS = listOf("hétfő", "kedd", "szerda", "csütörtök", "péntek", "szombat", "vasárnap")
