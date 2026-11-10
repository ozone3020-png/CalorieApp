package com.calorieapp.ui.log

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class LogFormatTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test fun `day titles`() {
        assertEquals("Today", dayTitle(today, today))
        assertEquals("Yesterday", dayTitle(today.minusDays(1), today))
        assertEquals(LocalDate.of(2026, 11, 3).let { dayTitle(it, today) }.contains("3"), true)
        assertEquals(true, dayTitle(LocalDate.of(2025, 12, 25), today).endsWith("2025"))
    }

    @Test fun `meal labels by time of day`() {
        assertEquals("Breakfast", mealLabel(LocalTime.of(7, 30)))
        assertEquals("Lunch", mealLabel(LocalTime.of(12, 45)))
        assertEquals("Afternoon snack", mealLabel(LocalTime.of(15, 30)))
        assertEquals("Dinner", mealLabel(LocalTime.of(18, 30)))
        assertEquals("Late snack", mealLabel(LocalTime.of(23, 0)))
        assertEquals("Late snack", mealLabel(LocalTime.of(1, 0)))
    }
}
