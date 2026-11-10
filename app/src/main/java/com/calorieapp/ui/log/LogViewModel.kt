package com.calorieapp.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.calorieapp.CalorieApplication
import com.calorieapp.data.DeletedFromLog
import com.calorieapp.data.LoggedFood
import com.calorieapp.data.LoggedMeal
import com.calorieapp.data.MealRepository
import com.calorieapp.data.NutritionTotals
import com.calorieapp.data.SettingsRepository
import com.calorieapp.data.UserSettings
import com.calorieapp.data.totals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class LogUiState(
    val day: LocalDate,
    val meals: List<LoggedMeal>,
    val settings: UserSettings,
    val isLoading: Boolean = false,
) {
    val totals: NutritionTotals get() = meals.flatMap { meal -> meal.foods.map { it.item } }.totals()
    val isToday get() = day == LocalDate.now()
}

/** Something just deleted, offered for Undo. */
data class PendingUndo(val message: String, val deleted: DeletedFromLog)

@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModel(
    private val meals: MealRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val day = MutableStateFlow(LocalDate.now())

    val state: StateFlow<LogUiState> = combine(
        day.flatMapLatest { d -> meals.observeDay(d).map { d to it } },
        settings.settings,
    ) { (d, list), s -> LogUiState(day = d, meals = list, settings = s) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LogUiState(day = LocalDate.now(), meals = emptyList(), settings = UserSettings(), isLoading = true),
        )

    /** Set when something is deleted; the screen shows a snackbar with Undo, then calls [undoShown]. */
    val undo = MutableStateFlow<PendingUndo?>(null)

    fun previousDay() = day.update { it.minusDays(1) }

    fun nextDay() = day.update { d -> d.plusDays(1).takeIf { !it.isAfter(LocalDate.now()) } ?: d }

    fun showDay(date: LocalDate) {
        day.value = minOf(date, LocalDate.now())
    }

    fun showToday() = showDay(LocalDate.now())

    fun deleteMeal(meal: LoggedMeal) = viewModelScope.launch {
        meals.deleteMeal(meal.id)?.let { undo.value = PendingUndo("Meal deleted", it) }
    }

    fun deleteFood(food: LoggedFood) = viewModelScope.launch {
        meals.deleteFood(food)?.let { undo.value = PendingUndo("${food.item.name} deleted", it) }
    }

    fun undoDelete(pending: PendingUndo) = viewModelScope.launch {
        undo.value = null
        meals.restore(pending.deleted)
    }

    fun undoShown() {
        undo.value = null
    }

    private inline fun MutableStateFlow<LocalDate>.update(f: (LocalDate) -> LocalDate) {
        value = f(value)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CalorieApplication).container
                LogViewModel(container.mealRepository, container.settingsRepository)
            }
        }
    }
}
