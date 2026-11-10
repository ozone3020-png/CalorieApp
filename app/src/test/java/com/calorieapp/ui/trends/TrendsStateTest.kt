package com.calorieapp.ui.trends

import com.calorieapp.data.DayTotals
import com.calorieapp.data.Goals
import com.calorieapp.data.NutritionTotals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TrendsStateTest {
    private val d = LocalDate.of(2026, 11, 4)
    private val goals = Goals(kcal = 2000.0, proteinG = 50.0, carbsG = 300.0, fatG = 70.0)

    private fun day(offset: Long, kcal: Double?) = DayTotals(
        day = d.plusDays(offset),
        logged = kcal != null,
        totals = if (kcal == null) NutritionTotals() else NutritionTotals(kcal, kcal / 20, kcal / 8, kcal / 40),
    )

    @Test fun `averages only count days that were logged`() {
        val s = TrendsState(days = listOf(day(0, 1800.0), day(1, null), day(2, 2200.0), day(3, null)), goals = goals)
        assertEquals(2, s.loggedDays.size)
        assertEquals(2000.0, s.average.calories, 1e-9)
        assertEquals(100.0, s.average.proteinG, 1e-9)
    }

    @Test fun `days within goal counts logged days at or under the energy goal`() {
        val s = TrendsState(days = listOf(day(0, 1800.0), day(1, 2000.0), day(2, 2400.0), day(3, null)), goals = goals)
        assertEquals(2, s.daysWithinGoal)
    }

    @Test fun `no logged days gives zero averages`() {
        val s = TrendsState(days = listOf(day(0, null), day(1, null)), goals = goals)
        assertEquals(0.0, s.average.calories, 1e-9)
        assertEquals(0, s.daysWithinGoal)
    }
}
