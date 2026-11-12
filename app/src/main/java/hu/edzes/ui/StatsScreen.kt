package hu.edzes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import hu.edzes.dao
import hu.edzes.data.Activity
import hu.edzes.model.formSeries
import hu.edzes.model.dayOfMs
import hu.edzes.model.monday
import hu.edzes.model.trainingLoad
import hu.edzes.model.trainingReport
import hu.edzes.model.zoneSeconds
import hu.edzes.prefs
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val WEEKS = 12
private val SPORTS = listOf(
    "run" to Color(0xFFEF6C00), "bike" to Color(0xFF1E88E5), "swim" to Color(0xFF00ACC1),
    "walk" to Color(0xFF7CB342), "gym" to Color(0xFF8E24AA),
)

private fun sportBucket(sport: String) = if (sport in setOf("run", "bike", "swim", "walk")) sport else "gym"

@Composable
fun StatsContent(activities: List<Activity>) {
    val ctx = LocalContext.current
    val maxHr = ctx.prefs.getInt("max_hr", 0).takeIf { it > 0 }
    val today = LocalDate.now()
    val firstWeek = today.monday().minusWeeks(WEEKS - 1L)
    val byWeek = (0 until WEEKS).map { w -> activities.filter { dayOfMs(it.startMs).monday() == firstWeek.plusWeeks(w.toLong()) } }

    ShareSection()

    val week = byWeek.last()
    Section("Ez a hét")
    Text(
        "${week.size} edzés · ${fmtClock(week.sumOf { it.durationS }.toLong())} · " +
            "futás ${fmtKm(week.filter { it.sport == "run" }.sumOf { it.distanceM ?: 0.0 })} · terhelés ${week.sumOf { trainingLoad(it, maxHr) }.toInt()}",
    )

    Section("Heti edzésidő (utolsó $WEEKS hét)")
    StackedBars(byWeek.map { w -> SPORTS.map { (s, _) -> w.filter { sportBucket(it.sport) == s }.sumOf { it.durationS }.toFloat() } }, SPORTS.map { it.second })
    Legend(listOf("Futás", "Kerékpár", "Úszás", "Séta", "Terem").mapIndexed { i, l -> SPORTS[i].second to l })
    Text("Legtöbb: ${fmtClock(byWeek.maxOf { w -> w.sumOf { it.durationS } }.toLong())} / hét", style = MaterialTheme.typography.bodySmall)

    Section("Heti idő pulzuszónánként")
    if (maxHr == null) Text("Add meg a max pulzusodat a Beállításokban, és megjelennek a zónaidők.", style = MaterialTheme.typography.bodyMedium)
    else {
        val zones = byWeek.map { w ->
            val sum = IntArray(6)
            w.forEach { a -> zoneSeconds(a.hrHist, maxHr)?.forEachIndexed { z, s -> sum[z] += s } }
            (1..5).map { sum[it].toFloat() }
        }
        if (zones.all { it.sum() == 0f }) Text("Még nincs részletes pulzusadat (élő pulzussal mért vagy Health Connectből átvett edzés kell hozzá).", style = MaterialTheme.typography.bodyMedium)
        StackedBars(zones, ZONE_COLORS.drop(1))
        Legend((1..5).map { ZONE_COLORS[it] to "Z$it" })
    }

    Section("Edzettség és fáradtság")
    // 180 napra visszamenőleg számolunk, hogy a görbe eleje ne nulláról induljon; az utolsó 12 hét látszik.
    val days = 180
    val daily = DoubleArray(days)
    activities.forEach { a ->
        val ago = ChronoUnit.DAYS.between(dayOfMs(a.startMs), today).toInt()
        if (ago in 0 until days) daily[days - 1 - ago] += trainingLoad(a, maxHr)
    }
    val form = formSeries(daily.toList()).takeLast(WEEKS * 7)
    val fitnessColor = MaterialTheme.colorScheme.primary
    val fatigueColor = MaterialTheme.colorScheme.error
    LineChart(listOf(fitnessColor to form.map { it.fitness.toFloat() }, fatigueColor to form.map { it.fatigue.toFloat() }))
    Legend(listOf(fitnessColor to "Edzettség (6 hetes átlag)", fatigueColor to "Fáradtság (1 hetes)"))
    val now = form.last()
    Text(
        "Edzettség ${now.fitness.toInt()} · fáradtság ${now.fatigue.toInt()} · forma ${now.form.toInt()}",
        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "A terhelés a zónákban töltött percek zónaszámmal súlyozva. Ha a fáradtság jóval az edzettség fölött van (erősen negatív forma), " +
            "ideje könnyebb napot tartani; verseny előtt a nulla körüli vagy enyhén pozitív forma az ideális.",
        Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
    )
}

private const val CLAUDE_REQUEST = "Csatoltam az edzésnaplómat. Elemezd: hogyan alakult a terhelésem és a fejlődésem, mire figyeljek, és mit javasolsz a következő hetekre?"

/** Az edzésnapló átadása egy Claude-beszélgetésnek: fájlként megosztva, vagy vágólapra másolva. */
@Composable
private fun ShareSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var weeks by remember { mutableIntStateOf(12) }
    Section("Elemzés Claude-dal")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(4, 12, 26).forEach { w -> FilterChip(weeks == w, onClick = { weeks = w }, label = { Text("$w hét") }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { scope.launch { shareReport(ctx, buildReport(ctx, weeks)) } }) { Text("Megosztás Claude-dal") }
        FilledTonalButton(onClick = {
            scope.launch {
                val text = CLAUDE_REQUEST + "\n\n" + buildReport(ctx, weeks)
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Edzésnapló", text))
                ctx.toast("Az edzésnapló a vágólapon – illeszd be egy Claude-beszélgetésbe")
            }
        }) { Text("Másolás") }
    }
}

suspend fun buildReport(ctx: Context, weeks: Int): String {
    val dao = ctx.dao
    return trainingReport(
        dao.activitiesSince(0), dao.allSetLogs().groupBy { it.activityId }, dao.allPlanned(),
        ctx.prefs.getInt("max_hr", 0).takeIf { it > 0 }, LocalDate.now(), weeks,
    )
}

private fun shareReport(ctx: Context, report: String) {
    val file = File(ctx.cacheDir, "share/edzesnaplo.md")
    file.parentFile!!.mkdirs()
    file.writeText(report)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file))
        putExtra(Intent.EXTRA_TEXT, CLAUDE_REQUEST)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // Ha a Claude app telepítve van és fogadja, közvetlenül oda megy; különben a megosztási lista jön fel.
    runCatching { ctx.startActivity(Intent(send).setPackage("com.anthropic.claude")) }
        .onFailure { ctx.startActivity(Intent.createChooser(send, "Edzésnapló megosztása")) }
}
