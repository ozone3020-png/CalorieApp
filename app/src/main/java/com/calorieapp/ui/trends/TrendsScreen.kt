package com.calorieapp.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.calorieapp.data.DayTotals
import com.calorieapp.data.EnergyUnit
import com.calorieapp.ui.common.LocalEnergyUnit
import com.calorieapp.ui.common.energyNumber
import com.calorieapp.ui.common.energyText
import com.calorieapp.ui.common.fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** Daily energy over 7 / 30 / 90 days against the goal, averages, and a day-by-day list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    state: TrendsState,
    onRangeChange: (TrendRange) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onBack: () -> Unit,
) {
    val unit = LocalEnergyUnit.current
    // Index into state.days of the tapped bar, if any.
    var selected by rememberSaveable(state.range) { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trends") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "range") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TrendRange.entries.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = state.range == r,
                            onClick = { onRangeChange(r) },
                            shape = SegmentedButtonDefaults.itemShape(i, TrendRange.entries.size),
                        ) { Text(r.label) }
                    }
                }
            }

            item(key = "headline") { Headline(state, unit) }

            item(key = "chart") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Daily energy (${unit.label})", style = MaterialTheme.typography.titleMedium)
                        SelectedDay(state, selected, unit, onOpenDay)
                        EnergyBars(
                            days = state.days,
                            goalKcal = state.goals.kcal,
                            unit = unit,
                            range = state.range,
                            selected = selected,
                            onSelect = { selected = if (selected == it) null else it },
                        )
                    }
                }
            }

            item(key = "macros") { MacroAverages(state) }

            val listed = state.loggedDays.reversed()
            if (listed.isNotEmpty()) {
                item(key = "list-header") {
                    Text("Days", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
                items(listed, key = { it.day.toEpochDay() }) { day ->
                    DayRow(day, state.goals.kcal, unit, onClick = { onOpenDay(day.day) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Headline(state: TrendsState, unit: EnergyUnit) {
    val logged = state.loggedDays.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HeroStat(
            value = if (logged == 0) "–" else energyNumber(state.average.calories, unit),
            label = "${unit.label} a day on average",
        )
        HeroStat(value = "$logged / ${state.days.size}", label = "days logged")
        HeroStat(value = if (logged == 0) "–" else "${state.daysWithinGoal}", label = "days within goal")
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectedDay(state: TrendsState, selected: Int?, unit: EnergyUnit, onOpenDay: (LocalDate) -> Unit) {
    val day = selected?.let { state.days.getOrNull(it) }
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                day == null -> "Tap a bar to see that day"
                !day.logged -> "${day.day.format(DAY)} · nothing logged"
                else -> "${day.day.format(DAY)} · ${energyText(day.totals.calories, unit)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (day == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (day != null) TextButton(onClick = { onOpenDay(day.day) }) { Text("Open day") }
    }
}

/**
 * One bar per day (empty slot for days with nothing logged), a dashed goal line, and sparse date labels.
 * Tapping anywhere in a day's column selects it; the column is a much bigger target than the bar.
 */
@Composable
private fun EnergyBars(
    days: List<DayTotals>,
    goalKcal: Double,
    unit: EnergyUnit,
    range: TrendRange,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val dimColor = barColor.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    val maxKcal = maxOf(goalKcal * 1.15, (days.maxOfOrNull { it.totals.calories } ?: 0.0) * 1.08).coerceAtLeast(1.0)
    val description = remember(days, goalKcal, unit) {
        val logged = days.filter { it.logged }
        "Bar chart of daily energy over ${days.size} days, ${logged.size} logged; goal ${energyText(goalKcal, unit)}."
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .semantics { contentDescription = description }
            .pointerInput(days.size) {
                detectTapGestures { offset ->
                    if (days.isNotEmpty()) onSelect((offset.x / (size.width / days.size)).toInt().coerceIn(0, days.lastIndex))
                }
            },
    ) {
        if (days.isEmpty()) return@Canvas
        val labelHeight = 20.dp.toPx()
        val plotHeight = size.height - labelHeight
        val slot = size.width / days.size
        val gap = 2.dp.toPx()
        val barWidth = (slot * if (days.size <= 7) 0.55f else 0.8f).coerceAtLeast(1f).let { minOf(it, slot - gap) }
        val radius = minOf(4.dp.toPx(), barWidth / 2)
        fun y(kcal: Double) = plotHeight - (kcal / maxKcal * plotHeight).toFloat()

        // Baseline
        drawLine(gridColor, Offset(0f, plotHeight), Offset(size.width, plotHeight), strokeWidth = 1.dp.toPx())

        days.forEachIndexed { i, d ->
            if (!d.logged || d.totals.calories <= 0) return@forEachIndexed
            val left = i * slot + (slot - barWidth) / 2
            val top = y(d.totals.calories)
            // Rounded top, square bottom anchored to the baseline.
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left, top = top, right = left + barWidth, bottom = plotHeight,
                        topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                        bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                    ),
                )
            }
            drawPath(path, if (selected == null || selected == i) barColor else dimColor)
        }

        // Goal line, dashed, labelled at the right.
        val goalY = y(goalKcal)
        drawLine(
            goalColor, Offset(0f, goalY), Offset(size.width, goalY),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        )
        val goalLabel = measurer.measure("Goal ${energyNumber(goalKcal, unit)}", labelStyle)
        drawText(goalLabel, topLeft = Offset(size.width - goalLabel.size.width, (goalY - goalLabel.size.height - 2.dp.toPx()).coerceAtLeast(0f)))

        // Sparse x labels: every day for a week, about weekly for a month, monthly for 90 days.
        days.forEachIndexed { i, d ->
            val text = when (range) {
                TrendRange.WEEK -> d.day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
                TrendRange.MONTH -> if (i % 7 == (days.size - 1) % 7) d.day.format(SHORT_DATE) else null
                TrendRange.QUARTER -> if (d.day.dayOfMonth == 1) d.day.format(MONTH) else null
            } ?: return@forEachIndexed
            val layout = measurer.measure(text, labelStyle)
            val x = (i * slot + slot / 2 - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, plotHeight + 4.dp.toPx()))
        }
    }
}

@Composable
private fun MacroAverages(state: TrendsState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Average macros per logged day", style = MaterialTheme.typography.titleMedium)
            AverageBar("Protein", state.average.proteinG, state.goals.proteinG, MaterialTheme.colorScheme.primary)
            AverageBar("Carbs", state.average.carbsG, state.goals.carbsG, MaterialTheme.colorScheme.secondary)
            AverageBar("Fat", state.average.fatG, state.goals.fatG, MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun AverageBar(label: String, value: Double, goal: Double, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "${Math.round(value)} / ${Math.round(goal)} g",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (goal <= 0) 0f else (value / goal).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun DayRow(day: DayTotals, goalKcal: Double, unit: EnergyUnit, onClick: () -> Unit) {
    val t = day.totals
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(day.day.format(DAY), style = MaterialTheme.typography.bodyLarge)
            Text(
                "P ${t.proteinG.fmt()} · C ${t.carbsG.fmt()} · F ${t.fatG.fmt()} g",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(energyText(t.calories, unit), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (t.calories <= goalKcal) "within goal" else "${energyText(t.calories - goalKcal, unit)} over",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
