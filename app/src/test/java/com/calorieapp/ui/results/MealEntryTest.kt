package com.calorieapp.ui.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class MealEntryTest {
    @Test fun `several foods are offered as a meal first`() {
        assertTrue(looksLikeMeal("2 eggs on toast"))
        assertTrue(looksLikeMeal("chicken and rice"))
        assertTrue(looksLikeMeal("toast, butter"))
        assertTrue(looksLikeMeal("porridge with banana"))
        assertFalse(looksLikeMeal("banana"))
        assertFalse(looksLikeMeal("flat white"))
        assertFalse("'brandy' contains 'and' but isn't two foods", looksLikeMeal("brandy"))
    }

    @Test fun `meal buttons match the labels in the log`() {
        assertEquals(MealSlot.BREAKFAST, MealSlot.of(MealSlot.BREAKFAST.time))
        assertEquals(MealSlot.LUNCH, MealSlot.of(MealSlot.LUNCH.time))
        assertEquals(MealSlot.DINNER, MealSlot.of(MealSlot.DINNER.time))
        assertEquals(MealSlot.SNACK, MealSlot.of(MealSlot.SNACK.time))
        assertEquals(MealSlot.SNACK, MealSlot.of(LocalTime.of(22, 30)))
    }
}
