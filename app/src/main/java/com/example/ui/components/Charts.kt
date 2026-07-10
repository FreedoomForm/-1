package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ============================================================================
   Charts.kt — Canvas-based Compose-графики для страницы «Отчёты».
   ----------------------------------------------------------------------------
   • LineChart          — линейный график динамики (выручка по неделям)
   • BarChart           — столбчатая диаграмма (платежи по неделям/месяцам)
   • HorizontalBarChart — горизонтальные столбцы (top renters/scooters)
   • DonutChart         — кольцевая диаграмма (доли статусов)
   • Sparkline          — мини-график без осей для встраивания в KPI-карточку
   • HeatmapGrid        — сетка ячеек с цветовой интенсивностью (простои)
   • FunnelChart        — воронка конверсии (арендаторы → платящие → оплачено)
   • RadarChart         — радарная диаграмма по 5-6 осям (health score)
   • ProgressRing       — кольцевой прогресс-бар (занятость)
   • KpiCard            — карточка метрики с индикатором роста/падения + sparkline
   • TrendDelta         — стрелка ▲/▼ + процент изменения
   ============================================================================ */

// ──────────────────────────────────────────────────────────────────────────────
// 1. Линейный график
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun LineChart(
    data: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    lineColor: Color = ClaudeAccent,
    fillColor: Color = ClaudeAccent.copy(alpha = 0.15f),
    height: androidx.compose.ui.unit.Dp = 140.dp
) {
    if (data.isEmpty()) {
        Box(modifier.height(height), contentAlignment = Alignment.Center) {
            Text("Ma'lumot yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
        }
        return
    }
    val maxValue = (data.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(1f)
    val minValue = (data.minOfOrNull { it.second } ?: 0f).coerceAtLeast(0f)
    val range = (maxValue - minValue).coerceAtLeast(1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 4.dp)
        ) {
            val w = size.width
            val h = size.height
            val padTop = 8f
            val padBottom = 22f
            val chartH = h - padTop - padBottom
            val stepX = if (data.size > 1) w / (data.size - 1) else w

            // ── Горизонтальные линии сетки ──
            val gridColor = ClaudeDivider
            for (i in 0..3) {
                val y = padTop + chartH * i / 3f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            // ── Площадь под линией (заливка) ──
            val path = Path()
            data.forEachIndexed { i, (_, v) ->
                val x = i * stepX
                val y = padTop + chartH * (1 - (v - minValue) / range)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Замыкаем для заливки
            path.lineTo(w, padTop + chartH)
            path.lineTo(0f, padTop + chartH)
            path.close()
            drawPath(path = path, color = fillColor)

            // ── Сама линия ──
            val linePath = Path()
            data.forEachIndexed { i, (_, v) ->
                val x = i * stepX
                val y = padTop + chartH * (1 - (v - minValue) / range)
                if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // ── Точки-маркеры на каждом значении ──
            data.forEachIndexed { i, (_, v) ->
                val x = i * stepX
                val y = padTop + chartH * (1 - (v - minValue) / range)
                drawCircle(color = Color.White, radius = 5f, center = Offset(x, y))
                drawCircle(color = lineColor, radius = 3.5f, center = Offset(x, y))
            }
        }
        // ── Подписи оси X ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            data.forEach { (label, _) ->
                Text(
                    text = label,
                    fontSize = 9.sp,
                    color = ClaudeTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 2. Столбчатая диаграмма (вертикальная)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun BarChart(
    data: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    barColor: Color = ClaudeAccent,
    height: androidx.compose.ui.unit.Dp = 140.dp,
    showValues: Boolean = true
) {
    if (data.isEmpty()) {
        Box(modifier.height(height), contentAlignment = Alignment.Center) {
            Text("Ma'lumot yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
        }
        return
    }
    val maxValue = (data.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 4.dp)
        ) {
            val w = size.width
            val h = size.height
            val padTop = 12f
            val padBottom = 22f
            val chartH = h - padTop - padBottom
            val barCount = data.size
            val totalGap = w * 0.3f
            val gap = if (barCount > 1) totalGap / (barCount - 1) else 0f
            val barW = (w - totalGap) / barCount

            // Сетка из 3 линий
            for (i in 0..3) {
                val y = padTop + chartH * i / 3f
                drawLine(
                    color = ClaudeDivider,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            // Столбцы
            data.forEachIndexed { i, (_, v) ->
                val x = i * (barW + gap)
                val barH = (v / maxValue) * chartH
                val y = padTop + chartH - barH
                // Скруглённый верх
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            data.forEach { (label, _) ->
                Text(
                    text = label,
                    fontSize = 9.sp,
                    color = ClaudeTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 3. Горизонтальная столбчатая диаграмма (для top-рейтингов)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun HorizontalBarChart(
    data: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    barColor: Color = ClaudeAccent,
    valueFormatter: (Float) -> String = { it.toInt().toString() }
) {
    if (data.isEmpty()) {
        Box(modifier.height(80.dp), contentAlignment = Alignment.Center) {
            Text("Ma'lumot yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
        }
        return
    }
    val maxValue = (data.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(1f)

    Column(modifier = modifier.fillMaxWidth()) {
        data.forEachIndexed { i, (label, v) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Номер рейтинга
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(ClaudeAccentBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${i + 1}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ClaudeAccent)
                }
                Spacer(Modifier.width(6.dp))
                // Имя
                Text(
                    label,
                    fontSize = 11.sp,
                    color = ClaudeText,
                    maxLines = 1,
                    modifier = Modifier.width(80.dp)
                )
                Spacer(Modifier.width(6.dp))
                // Полоса
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(ClaudeDivider)
                ) {
                    val ratio = (v / maxValue).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(7.dp))
                            .background(barColor)
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Значение
                Text(
                    valueFormatter(v),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ClaudeText,
                    modifier = Modifier.width(60.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 4. Кольцевая диаграмма
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun DonutChart(
    segments: List<Pair<String, Int>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    centerLabel: String? = null,
    centerValue: String? = null,
    chartSize: androidx.compose.ui.unit.Dp = 120.dp
) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    val sweepAngles = segments.map { (it.second.toFloat() / total) * 360f }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(chartSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(chartSize)) {
                val drawSize = this.size
                val diameter = min(drawSize.width, drawSize.height)
                val strokeW = diameter * 0.18f
                val rectF = Rect(
                    offset = Offset(strokeW / 2, strokeW / 2),
                    size = Size(diameter - strokeW, diameter - strokeW)
                )
                var startAngle = -90f
                // Фон-кольцо
                drawArc(
                    color = ClaudeDivider,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = rectF.topLeft,
                    size = rectF.size,
                    style = Stroke(width = strokeW)
                )
                segments.forEachIndexed { i, _ ->
                    drawArc(
                        color = colors.getOrNull(i) ?: ClaudeAccent,
                        startAngle = startAngle,
                        sweepAngle = sweepAngles[i],
                        useCenter = false,
                        topLeft = rectF.topLeft,
                        size = rectF.size,
                        style = Stroke(width = strokeW, cap = StrokeCap.Round)
                    )
                    startAngle += sweepAngles[i]
                }
            }
            if (centerValue != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        centerValue,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClaudeText
                    )
                    if (centerLabel != null) {
                        Text(centerLabel, fontSize = 9.sp, color = ClaudeTextSecondary)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        // Легенда
        Column {
            segments.forEachIndexed { i, (label, value) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors.getOrNull(i) ?: ClaudeAccent)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$label: $value",
                        fontSize = 11.sp,
                        color = ClaudeText
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 5. Спарклайн (мини-график для KPI-карточки)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun Sparkline(
    data: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    height: androidx.compose.ui.unit.Dp = 28.dp
) {
    if (data.size < 2) return
    val maxV = data.max().coerceAtLeast(1f)
    val minV = data.min().coerceAtLeast(0f)
    val range = (maxV - minV).coerceAtLeast(1f)

    Canvas(modifier = modifier
        .fillMaxWidth()
        .height(height)
    ) {
        val w = size.width
        val h = size.height
        val stepX = w / (data.size - 1)

        // Заливка
        val fillPath = Path().apply {
            data.forEachIndexed { i, v ->
                val x = i * stepX
                val y = h - (v - minV) / range * h
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(fillPath, color = color.copy(alpha = 0.25f))

        // Линия
        val linePath = Path().apply {
            data.forEachIndexed { i, v ->
                val x = i * stepX
                val y = h - (v - minV) / range * h
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(linePath, color = color, style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 6. Тепловая карта (heatmap) — простои по скутерам
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun HeatmapGrid(
    rows: List<String>,           // подписи строк (скутеры)
    cols: List<String>,           // подписи колонок (недели)
    values: List<List<Float>>,    // 0..1 — интенсивность цвета
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty() || cols.isEmpty()) {
        Box(modifier.height(80.dp), contentAlignment = Alignment.Center) {
            Text("Ma'lumot yo'q", color = ClaudeTextSecondary, fontSize = 12.sp)
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        // Шапка с подписями колонок
        Row(modifier = Modifier.fillMaxWidth().padding(start = 60.dp)) {
            cols.forEach { col ->
                Text(
                    col,
                    fontSize = 9.sp,
                    color = ClaudeTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // Строки
        rows.forEachIndexed { rowIdx, rowLabel ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    rowLabel,
                    fontSize = 10.sp,
                    color = ClaudeText,
                    maxLines = 1,
                    modifier = Modifier.width(60.dp)
                )
                cols.forEachIndexed { colIdx, _ ->
                    val v = values.getOrNull(rowIdx)?.getOrNull(colIdx) ?: 0f
                    val cellColor = when {
                        v >= 0.75f -> StatusOverdue
                        v >= 0.5f -> Color(0xFFF97316)
                        v >= 0.25f -> ClaudeGold
                        v > 0f -> StatusOk.copy(alpha = 0.6f)
                        else -> StatusOk
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(cellColor)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Легенда
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Kam", fontSize = 9.sp, color = ClaudeTextSecondary)
            listOf(StatusOk, StatusOk.copy(alpha = 0.6f), ClaudeGold, Color(0xFFF97316), StatusOverdue).forEach { c ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(c)
                )
            }
            Text("Ko'p", fontSize = 9.sp, color = ClaudeTextSecondary)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 7. Воронка конверсии
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun FunnelChart(
    stages: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(ClaudeAccent, ClaudeGold, StatusOk, ClaudeTeal)
) {
    if (stages.isEmpty()) return
    val maxValue = stages.maxOfOrNull { it.second }?.toFloat() ?: 1f

    Column(modifier = modifier.fillMaxWidth()) {
        stages.forEachIndexed { i, (label, value) ->
            val ratio = (value.toFloat() / maxValue).coerceIn(0f, 1f)
            val conversionPct = if (i > 0 && stages[i - 1].second > 0) {
                (value.toFloat() / stages[i - 1].second * 100).toInt()
            } else 100

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = ClaudeText,
                    modifier = Modifier.width(80.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        colors.getOrNull(i) ?: ClaudeAccent,
                                        (colors.getOrNull(i) ?: ClaudeAccent).copy(alpha = 0.6f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            value.toString(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (i > 0) {
                    Text(
                        "$conversionPct%",
                        fontSize = 10.sp,
                        color = ClaudeTextSecondary,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.End
                    )
                } else {
                    Spacer(Modifier.width(36.dp))
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 8. Радарная диаграмма (health score по нескольким осям)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun RadarChart(
    axes: List<Pair<String, Float>>,   // 0..1 normalized
    modifier: Modifier = Modifier,
    color: Color = ClaudeAccent,
    chartSize: androidx.compose.ui.unit.Dp = 180.dp
) {
    if (axes.size < 3) return

    Box(
        modifier = modifier.size(chartSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(chartSize)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = min(w, h) / 2f * 0.85f
            val n = axes.size

            // ── Сетка-концентрические многоугольники ──
            for (level in 1..4) {
                val r = radius * level / 4f
                val polyPath = Path()
                for (i in 0 until n) {
                    val angle = -Math.PI / 2 + 2 * Math.PI * i / n
                    val x = cx + (r * cos(angle)).toFloat()
                    val y = cy + (r * sin(angle)).toFloat()
                    if (i == 0) polyPath.moveTo(x, y) else polyPath.lineTo(x, y)
                }
                polyPath.close()
                drawPath(polyPath, color = ClaudeDivider, style = Stroke(width = 1f))
            }

            // ── Оси ──
            for (i in 0 until n) {
                val angle = -Math.PI / 2 + 2 * Math.PI * i / n
                val x = cx + (radius * cos(angle)).toFloat()
                val y = cy + (radius * sin(angle)).toFloat()
                drawLine(
                    color = ClaudeDivider,
                    start = Offset(cx, cy),
                    end = Offset(x, y),
                    strokeWidth = 1f
                )
            }

            // ── Многоугольник значений ──
            val dataPath = Path()
            for (i in 0 until n) {
                val angle = -Math.PI / 2 + 2 * Math.PI * i / n
                val r = radius * axes[i].second.coerceIn(0f, 1f)
                val x = cx + (r * cos(angle)).toFloat()
                val y = cy + (r * sin(angle)).toFloat()
                if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
            }
            dataPath.close()
            drawPath(dataPath, color = color.copy(alpha = 0.25f))
            drawPath(dataPath, color = color, style = Stroke(width = 2f))

            // ── Точки на вершинах ──
            for (i in 0 until n) {
                val angle = -Math.PI / 2 + 2 * Math.PI * i / n
                val r = radius * axes[i].second.coerceIn(0f, 1f)
                val x = cx + (r * cos(angle)).toFloat()
                val y = cy + (r * sin(angle)).toFloat()
                drawCircle(color = color, radius = 4f, center = Offset(x, y))
            }
        }
    }
    // Подписи осей — отдельной колонкой под графиком
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        axes.forEach { (label, value) ->
            Text(
                "$label: ${(value * 100).toInt()}%",
                fontSize = 10.sp,
                color = ClaudeTextSecondary
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 9. Кольцевой прогресс-бар
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun ProgressRing(
    percent: Int,
    modifier: Modifier = Modifier,
    color: Color = StatusOk,
    chartSize: androidx.compose.ui.unit.Dp = 80.dp,
    label: String = ""
) {
    val safePercent = percent.coerceIn(0, 100)
    Box(
        modifier = modifier.size(chartSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(chartSize)) {
            val drawSize = this.size
            val diameter = min(drawSize.width, drawSize.height)
            val strokeW = diameter * 0.12f
            val rectF = Rect(
                offset = Offset(strokeW / 2, strokeW / 2),
                size = Size(diameter - strokeW, diameter - strokeW)
            )
            // Фон
            drawArc(
                color = ClaudeDivider,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = rectF.topLeft,
                size = rectF.size,
                style = Stroke(width = strokeW)
            )
            // Прогресс
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * safePercent / 100f,
                useCenter = false,
                topLeft = rectF.topLeft,
                size = rectF.size,
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$safePercent%",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            if (label.isNotEmpty()) {
                Text(label, fontSize = 8.sp, color = ClaudeTextSecondary)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 10. KPI-карточка с индикатором роста/падения + спарклайн
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun KpiCard(
    title: String,
    value: String,
    deltaPercent: Int? = null,
    deltaPositive: Boolean = true,
    sparkline: List<Float> = emptyList(),
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    accentColor: Color = ClaudeAccent,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(accentColor, accentColor.copy(alpha = 0.7f))))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    androidx.compose.material3.Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (deltaPercent != null) {
                    TrendDelta(deltaPercent, deltaPositive, Color.White)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (sparkline.size >= 2) {
                Spacer(Modifier.height(4.dp))
                Sparkline(sparkline, color = Color.White.copy(alpha = 0.8f), height = 24.dp)
            }
        }
    }
}

@Composable
fun TrendDelta(
    percent: Int,
    positive: Boolean,
    textColor: Color = ClaudeText
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (positive) "▲" else "▼",
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(2.dp))
        Text(
            "${if (positive) "+" else "−"}$percent%",
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 11. Сводная таблица с сортировкой (простая версия)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun SimpleDataTable(
    headers: List<String>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    headerColors: List<Color> = emptyList()
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, ClaudeDivider, RoundedCornerShape(8.dp))
            .background(ClaudeCard)
    ) {
        // Заголовок
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ClaudeAccentBg)
                .padding(vertical = 6.dp, horizontal = 8.dp)
        ) {
            headers.forEachIndexed { i, h ->
                Text(
                    h,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClaudeText,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Строки
        rows.forEachIndexed { rowIdx, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 8.dp)
                    .background(if (rowIdx % 2 == 0) Color.Transparent else ClaudeBackground2.copy(alpha = 0.3f))
            ) {
                row.forEachIndexed { colIdx, cell ->
                    val cellColor = headerColors.getOrNull(colIdx) ?: ClaudeText
                    Text(
                        cell,
                        fontSize = 10.sp,
                        color = if (colIdx == row.size - 1) cellColor else ClaudeText,
                        fontWeight = if (colIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 12. Статусный чип (зелёный/жёлтый/красный)
// ──────────────────────────────────────────────────────────────────────────────
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}
