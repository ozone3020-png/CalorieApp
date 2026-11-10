package com.calorieapp.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calorieapp.data.EnergyUnit
import com.calorieapp.data.Goals
import com.calorieapp.data.LoggedFood
import com.calorieapp.data.LoggedMeal
import com.calorieapp.data.NutritionSource
import com.calorieapp.data.NutritionTotals
import com.calorieapp.ui.common.LocalEnergyUnit
import com.calorieapp.ui.common.energyNumber
import com.calorieapp.ui.common.energyText
import com.calorieapp.ui.common.fmt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/** Step 4: the home screen. A day's totals against goals, and the meals logged that day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    state: LogUiState,
    undo: PendingUndo?,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onPickDay: (LocalDate) -> Unit,
    onAddMeal: () -> Unit,
    onTypeFood: () -> Unit,
    onEditMeal: (LoggedMeal) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrends: () -> Unit,
    onDeleteMeal: (LoggedMeal) -> Unit,
    onDeleteFood: (LoggedFood) -> Unit,
    onUndo: (PendingUndo) -> Unit,
    onUndoShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(undo) {
        if (undo != null) {
            val result = snackbar.showSnackbar(undo.message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onUndo(undo) else onUndoShown()
        }
    }

    if (pickingDate) {
        DayPickerDialog(
            day = state.day,
            onPick = { pickingDate = false; onPickDay(it) },
            onDismiss = { pickingDate = false },
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onPreviousDay) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                    }
                },
                title = {
                    TextButton(onClick = { pickingDate = true }) {
                        Text(dayTitle(state.day), style = MaterialTheme.typography.titleLarge)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick a date")
                    }
                },
                actions = {
                    IconButton(onClick = onNextDay, enabled = !state.isToday) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                    }
                    IconButton(onClick = onOpenTrends) {
                        Icon(Icons.Filled.BarChart, contentDescription = "Trends")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = onTypeFood) {
                    Icon(Icons.Filled.Edit, contentDescription = "Type a food")
                }
                Spacer(Modifier.height(12.dp))
                ExtendedFloatingActionButton(
                    onClick = onAddMeal,
                    icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                    text = { Text("Add meal") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp, // clear of the FAB
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                SummaryCard(state.totals, state.settings.goals)
            }
            if (state.meals.isEmpty() && !state.isLoading) {
                item(key = "empty") { EmptyDay(state.isToday) }
            }
            items(state.meals, key = { it.id }) { meal ->
                MealCard(meal, onEdit = { onEditMeal(meal) }, onDeleteMeal = { onDeleteMeal(meal) }, onDeleteFood = onDeleteFood)
            }
        }
    }
}

@Composable
private fun SummaryCard(totals: NutritionTotals, goals: Goals) {
    val unit = LocalEnergyUnit.current
    val left = goals.kcal - totals.calories
    val over = left < 0
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { fraction(totals.calories, goals.kcal) },
                    modifier = Modifier.size(132.dp),
                    strokeWidth = 12.dp,
                    strokeCap = StrokeCap.Round,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        energyNumber(totals.calories, unit),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "of ${energyNumber(goals.kcal, unit)} ${unit.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (over) "${energyText(abs(left), unit)} over" else "${energyText(left, unit)} left",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                MacroBar("Protein", totals.proteinG, goals.proteinG, MaterialTheme.colorScheme.primary)
                MacroBar("Carbs", totals.carbsG, goals.carbsG, MaterialTheme.colorScheme.secondary)
                MacroBar("Fat", totals.fatG, goals.fatG, MaterialTheme.colorScheme.tertiary)
                MacroBar("Fibre", totals.fibreG, goals.fibreG, MaterialTheme.colorScheme.primary, overIsBad = false)
            }
        }
        // Fruit and veg serves (Australian Dietary Guidelines): goals to reach, not limits.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.weight(1f)) {
                MacroBar("Fruit", totals.fruitServes, goals.fruitServes, MaterialTheme.colorScheme.secondary, unit = "serves", overIsBad = false)
            }
            Box(Modifier.weight(1f)) {
                MacroBar("Veg", totals.vegServes, goals.vegServes, MaterialTheme.colorScheme.tertiary, unit = "serves", overIsBad = false)
            }
        }
    }
}

@Composable
private fun MacroBar(
    label: String,
    value: Double,
    goal: Double,
    color: Color,
    unit: String = "g",
    // Fat, carbs and protein are limits (red when over); fibre and fruit/veg serves are targets.
    overIsBad: Boolean = true,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                "${value.fmtMacro()} / ${goal.fmtMacro()} $unit",
                style = MaterialTheme.typography.labelMedium,
                color = if (overIsBad && value > goal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction(value, goal) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun MealCard(
    meal: LoggedMeal,
    onEdit: () -> Unit,
    onDeleteMeal: () -> Unit,
    onDeleteFood: (LoggedFood) -> Unit,
) {
    val unit = LocalEnergyUnit.current
    val time = meal.loggedAt.atZone(ZoneId.systemDefault())
    // Tapping the card opens the meal for editing.
    ElevatedCard(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(mealLabel(time.toLocalTime()), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${time.format(TIME_FORMAT)} · ${energyText(meal.totals.calories, unit)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDeleteMeal) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete meal")
                }
            }
            meal.foods.forEachIndexed { i, food ->
                if (i > 0) HorizontalDivider(Modifier.padding(end = 12.dp))
                FoodRow(food, unit, onDelete = { onDeleteFood(food) })
            }
        }
    }
}

@Composable
private fun FoodRow(food: LoggedFood, unit: EnergyUnit, onDelete: () -> Unit) {
    val item = food.item
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.grams.fmt()} ${item.unit.label} · P ${item.proteinG.fmt()} · C ${item.carbsG.fmt()} · " +
                    "F ${item.fatG.fmt()} · Fb ${item.fibreG.fmt()}" +
                    (item.plantGroup?.let { " · ${it.label} ${item.serves.fmt()}" } ?: "") +
                    sourceTag(item.source).let { if (it.isEmpty()) "" else " · $it" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            energyText(item.calories, unit),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Outlined.RemoveCircleOutline,
                contentDescription = "Delete ${item.name}",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyDay(isToday: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.Restaurant,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (isToday) {
                "Nothing logged yet today.\nTap Add meal to photograph your food, or ✎ to type it in."
            } else {
                "Nothing logged this day.\nAnything you add now is logged on this day."
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(day: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The date picker works in UTC midnight milliseconds.
    val todayMillis = LocalDate.now().toUtcMillis()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = day.toUtcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    ?: onDismiss()
            }) { Text("Show") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(pickerState)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

internal fun dayTitle(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(if (day.year == today.year) DAY_FORMAT else DateTimeFormatter.ofPattern("d MMM yyyy"))
}

/** A friendly name for when a meal was logged. */
internal fun mealLabel(time: LocalTime): String = when {
    time < LocalTime.of(4, 0) -> "Late snack"
    time < LocalTime.of(10, 30) -> "Breakfast"
    time < LocalTime.of(11, 30) -> "Morning snack"
    time < LocalTime.of(14, 30) -> "Lunch"
    time < LocalTime.of(17, 0) -> "Afternoon snack"
    time < LocalTime.of(21, 0) -> "Dinner"
    else -> "Late snack"
}

private fun sourceTag(source: NutritionSource) = when (source) {
    NutritionSource.AI -> "AI est."
    NutritionSource.UNKNOWN -> ""
    NutritionSource.LABEL -> "Label"
    NutritionSource.OFF -> "OFF"
    else -> source.label
}

private fun fraction(value: Double, goal: Double): Float =
    if (goal <= 0) 0f else (value / goal).toFloat().coerceIn(0f, 1f)

private fun Double.fmtMacro() = if (this >= 10) Math.round(this).toString() else fmt()

private fun LocalDate.toUtcMillis() = toEpochDay() * 86_400_000L
