package com.calorieapp.ui.results

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.calorieapp.ui.log.dayTitle
import com.calorieapp.ui.log.mealLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Quick choices; picking one sets a typical time for it, which can then be fine-tuned. */
internal enum class MealSlot(val label: String, val time: LocalTime) {
    BREAKFAST("Breakfast", LocalTime.of(8, 0)),
    LUNCH("Lunch", LocalTime.of(12, 30)),
    DINNER("Dinner", LocalTime.of(18, 30)),
    SNACK("Snack", LocalTime.of(15, 0));

    companion object {
        /** Which quick choice a time falls under (matches the labels shown in the log). */
        fun of(time: LocalTime): MealSlot = when (mealLabel(time)) {
            "Breakfast" -> BREAKFAST
            "Lunch" -> LUNCH
            "Dinner" -> DINNER
            else -> SNACK
        }
    }
}

/** When the meal was eaten: so breakfast added at lunchtime can still be logged as breakfast. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhenCard(
    day: LocalDate,
    time: LocalTime,
    enabled: Boolean,
    onTimeChange: (LocalTime) -> Unit,
    onDayChange: (LocalDate) -> Unit,
) {
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    if (pickingTime) {
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("When did you eat this?") },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = { pickingTime = false; onTimeChange(LocalTime.of(state.hour, state.minute)) }) {
                    Text("OK")
                }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
        )
    }

    if (pickingDay) {
        // The date picker works in UTC midnight milliseconds.
        val todayMillis = LocalDate.now().toEpochDay() * 86_400_000L
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.toEpochDay() * 86_400_000L,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDay = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDay = false
                    state.selectedDateMillis?.let { onDayChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDay = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("When", style = MaterialTheme.typography.titleSmall)
            val current = MealSlot.of(time)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealSlot.entries.forEach { slot ->
                    FilterChip(
                        selected = slot == current,
                        onClick = { onTimeChange(slot.time) },
                        enabled = enabled,
                        label = { Text(slot.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingDay = true }, enabled = enabled) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer8()
                    Text(dayTitle(day))
                }
                OutlinedButton(onClick = { pickingTime = true }, enabled = enabled) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer8()
                    Text(time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
                }
            }
        }
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
