package hu.edzes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hu.edzes.data.DONE
import hu.edzes.data.SKIPPED
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy. LLLL", HU)

@Composable
fun CalendarScreen() {
    val (planned, activities) = rememberData()
    val start = rememberStarter()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var openActivity by remember { mutableStateOf<Long?>(null) }
    val plannedByDay = planned.groupBy { LocalDate.ofEpochDay(it.date) }
    val activitiesByDay = activities.groupBy { dayOf(it.startMs) }
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Előző hónap") }
            Text(month.format(MONTH_FORMAT), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Következő hónap") }
        }
        Row {
            listOf("H", "K", "Sze", "Cs", "P", "Szo", "V").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        for (row in 0 until (offset + month.lengthOfMonth() + 6) / 7) Row {
            for (col in 0..6) {
                val dayNo = row * 7 + col - offset + 1
                if (dayNo !in 1..month.lengthOfMonth()) { Spacer(Modifier.weight(1f)); continue }
                val date = month.atDay(dayNo)
                val plans = plannedByDay[date].orEmpty()
                Column(
                    Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (date == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { selected = date },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "$dayNo", fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        plans.take(3).forEach {
                            Dot(when (it.status) {
                                DONE -> MaterialTheme.colorScheme.primary
                                SKIPPED -> MaterialTheme.colorScheme.outlineVariant
                                else -> MaterialTheme.colorScheme.tertiary
                            })
                        }
                        // Terv nélküli edzés
                        if (plans.none { it.status == DONE } && date in activitiesByDay) Dot(MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(fmtDay(selected).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
        val plans = plannedByDay[selected].orEmpty()
        val acts = activitiesByDay[selected].orEmpty()
        if (plans.isEmpty() && acts.isEmpty()) Text("Nincs edzés ezen a napon.", style = MaterialTheme.typography.bodyMedium)
        plans.forEach { PlannedCard(it, start) }
        acts.forEach { a -> ActivityRow(a) { openActivity = a.id } }
    }
    activities.firstOrNull { it.id == openActivity }?.let { ActivityDialog(it) { openActivity = null } }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) = Box(Modifier.size(6.dp).clip(CircleShape).background(color))
