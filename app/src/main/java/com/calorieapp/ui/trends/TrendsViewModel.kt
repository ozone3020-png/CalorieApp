package com.calorieapp.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.calorieapp.CalorieApplication
import com.calorieapp.data.DayTotals
import com.calorieapp.data.Goals
import com.calorieapp.data.MealRepository
import com.calorieapp.data.NutritionTotals
import com.calorieapp.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

enum class TrendRange(val days: Int, val label: String) {
    WEEK(7, "7 days"),
    MONTH(30, "30 days"),
    QUARTER(90, "90 days"),
}

data class TrendsState(
    val range: TrendRange = TrendRange.WEEK,
    val days: List<DayTotals> = emptyList(),
    val goals: Goals = Goals.AU_REFERENCE,
) {
    val loggedDays get() = days.filter { it.logged }

    /** Averages over the days something was logged (an empty day would drag the average down misleadingly). */
    val average: NutritionTotals
        get() {
            val logged = loggedDays
            if (logged.isEmpty()) return NutritionTotals()
            val n = logged.size
            return NutritionTotals(
                calories = logged.sumOf { it.totals.calories } / n,
                proteinG = logged.sumOf { it.totals.proteinG } / n,
                carbsG = logged.sumOf { it.totals.carbsG } / n,
                fatG = logged.sumOf { it.totals.fatG } / n,
                fibreG = logged.sumOf { it.totals.fibreG } / n,
                fruitServes = logged.sumOf { it.totals.fruitServes } / n,
                vegServes = logged.sumOf { it.totals.vegServes } / n,
            )
        }

    /** Logged days that stayed at or under the energy goal. */
    val daysWithinGoal get() = loggedDays.count { it.totals.calories <= goals.kcal }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(meals: MealRepository, settings: SettingsRepository) : ViewModel() {

    private val range = MutableStateFlow(TrendRange.WEEK)

    val state: StateFlow<TrendsState> = combine(
        range.flatMapLatest { r ->
            val today = LocalDate.now()
            meals.observeDailyTotals(today.minusDays(r.days - 1L), today)
        },
        range,
        settings.settings,
    ) { days, r, s -> TrendsState(range = r, days = days, goals = s.goals) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState())

    fun selectRange(r: TrendRange) {
        range.value = r
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as CalorieApplication).container
                TrendsViewModel(c.mealRepository, c.settingsRepository)
            }
        }
    }
}
