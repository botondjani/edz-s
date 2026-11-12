package hu.edzes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos

/** Zónaszínek, index = zóna (0 = Z1 alatt). */
val ZONE_COLORS = listOf(Color(0xFF9E9E9E), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFCA28), Color(0xFFFF7043), Color(0xFFE53935))

private val CHART = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 4.dp)

/** Egy vagy több vonal közös függőleges skálán. invert: a kisebb érték van felül (tempónál a gyorsabb). */
@Composable
fun LineChart(series: List<Pair<Color, List<Float>>>, invert: Boolean = false) {
    val all = series.flatMap { it.second }
    if (all.size < 2) return
    val lo = all.min()
    val span = (all.max() - lo).takeIf { it > 0 } ?: 1f
    Canvas(CHART) {
        for ((color, values) in series) {
            if (values.size < 2) continue
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (values.size - 1)
                val f = (v - lo) / span
                val y = size.height * (if (invert) f else 1 - f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Halmozott oszlopok (pl. hetek), az oszlopon belüli részek a megadott színekkel. */
@Composable
fun StackedBars(bars: List<List<Float>>, colors: List<Color>) {
    val top = bars.maxOfOrNull { it.sum() }?.takeIf { it > 0 } ?: return
    Canvas(CHART) {
        val slot = size.width / bars.size
        bars.forEachIndexed { i, parts ->
            var y = size.height
            parts.forEachIndexed { j, v ->
                val h = size.height * v / top
                y -= h
                drawRect(colors[j], Offset(i * slot + slot * 0.15f, y), Size(slot * 0.7f, h))
            }
        }
    }
}

/** Az útvonal vonala (térképcsempék nélkül), zöld rajt- és piros célponttal. */
@Composable
fun RouteMap(points: List<Pair<Double, Double>>) {
    if (points.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(220.dp).padding(8.dp)) {
        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLon = points.minOf { it.second }
        val maxLon = points.maxOf { it.second }
        val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))   // a hosszúsági fokok a sarkok felé rövidülnek
        val w = ((maxLon - minLon) * lonScale).coerceAtLeast(1e-6)
        val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val scale = minOf(size.width / w, size.height / h)
        fun at(p: Pair<Double, Double>) = Offset(
            ((size.width - w * scale) / 2 + (p.second - minLon) * lonScale * scale).toFloat(),
            ((size.height - h * scale) / 2 + (maxLat - p.first) * scale).toFloat(),
        )
        val path = Path()
        points.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Color(0xFF2E7D32), 6.dp.toPx(), at(points.first()))
        drawCircle(Color(0xFFC62828), 6.dp.toPx(), at(points.last()))
    }
}

@Composable
fun Legend(items: List<Pair<Color, String>>) = Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    items.forEach { (color, label) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(" $label", style = MaterialTheme.typography.labelSmall)
        }
    }
}
