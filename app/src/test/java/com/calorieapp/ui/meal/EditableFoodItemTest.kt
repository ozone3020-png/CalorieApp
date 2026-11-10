package com.calorieapp.ui.meal

import com.calorieapp.data.FoodItem
import com.calorieapp.data.FoodUnit
import com.calorieapp.data.NutritionSource
import com.calorieapp.data.totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableFoodItemTest {
    private val spaghetti = FoodItem("spaghetti", grams = 250.0, calories = 350.0, proteinG = 10.0, carbsG = 60.0, fatG = 2.0)

    @Test fun `starts with the model's values`() {
        val item = EditableFoodItem(key = 1, original = spaghetti)
        assertEquals("250", item.gramsText)
        assertEquals(spaghetti, item.current)
    }

    @Test fun `changing grams scales nutrition linearly`() {
        val item = EditableFoodItem(key = 1, original = spaghetti, gramsText = "200")
        val c = item.current!!
        assertEquals(200.0, c.grams, 1e-9)
        assertEquals(280.0, c.calories, 1e-9)
        assertEquals(8.0, c.proteinG, 1e-9)
        assertEquals(48.0, c.carbsG, 1e-9)
        assertEquals(1.6, c.fatG, 1e-9)
    }

    @Test fun `repeated edits rescale from the original, not compounding`() {
        var item = EditableFoodItem(key = 1, original = spaghetti)
        item = item.copy(gramsText = "100")
        item = item.copy(gramsText = "500")
        assertEquals(700.0, item.current!!.calories, 1e-9)
    }

    @Test fun `accepts decimal comma and surrounding spaces`() {
        assertEquals(125.5, EditableFoodItem(1, spaghetti, gramsText = " 125,5 ").grams!!, 1e-9)
    }

    @Test fun `rejects empty, zero, negative, non-numeric and absurd weights`() {
        for (text in listOf("", "0", "-5", "abc", "5001")) {
            val item = EditableFoodItem(1, spaghetti, gramsText = text)
            assertNull("'$text' should be invalid", item.grams)
            assertNull(item.current)
            assertFalse(item.isValid)
        }
    }

    @Test fun `renaming keeps nutrition and trims the name`() {
        val item = EditableFoodItem(1, spaghetti, name = "  wholemeal spaghetti ")
        assertEquals("wholemeal spaghetti", item.current!!.name)
        assertEquals(350.0, item.current!!.calories, 1e-9)
        assertFalse(EditableFoodItem(1, spaghetti, name = "   ").isValid)
    }

    @Test fun `unknown original weight keeps nutrition as-is`() {
        val unknown = spaghetti.copy(grams = 0.0)
        val c = EditableFoodItem(1, unknown, gramsText = "100").current!!
        assertEquals(100.0, c.grams, 1e-9)
        assertEquals(350.0, c.calories, 1e-9)
    }

    @Test fun `totals sum edited items`() {
        val items = listOf(
            EditableFoodItem(1, spaghetti, gramsText = "125"),
            EditableFoodItem(2, FoodItem("parmesan", 30.0, 120.0, 7.0, 1.0, 9.0)),
        ).mapNotNull { it.current }
        val t = items.totals()
        assertEquals(295.0, t.calories, 1e-9)
        assertEquals(12.0, t.proteinG, 1e-9)
        assertTrue(t.fatG > 9.0)
    }

    @Test fun `renaming needs a lookup, but case and spacing changes don't`() {
        assertFalse(EditableFoodItem(1, spaghetti).needsLookup)
        assertFalse(EditableFoodItem(1, spaghetti, name = " Spaghetti ").needsLookup)
        assertTrue(EditableFoodItem(1, spaghetti, name = "rice").needsLookup)
        assertFalse("blank names are invalid, not looked up", EditableFoodItem(1, spaghetti, name = " ").needsLookup)
    }

    @Test fun `lookup uses the typed weight, falling back to the original then 100 g`() {
        assertEquals(180.0, EditableFoodItem(1, spaghetti, gramsText = "180").lookupGrams, 1e-9)
        assertEquals(250.0, EditableFoodItem(1, spaghetti, gramsText = "").lookupGrams, 1e-9)
        assertEquals(100.0, EditableFoodItem(1, spaghetti.copy(grams = 0.0), gramsText = "").lookupGrams, 1e-9)
    }

    @Test fun `scaling keeps the nutrition source`() {
        val rice = FoodItem("rice", 100.0, 158.0, 3.1, 34.7, 0.2, NutritionSource.AFCD, "Rice, white, boiled")
        val c = EditableFoodItem(1, rice, gramsText = "200").current!!
        assertEquals(NutritionSource.AFCD, c.source)
        assertEquals("Rice, white, boiled", c.match)
        assertEquals(316.0, c.calories, 1e-9)
    }

    @Test fun `unit follows the original and can be overridden`() {
        val cola = FoodItem("cola", 375.0, 192.0, 0.0, 48.0, 0.0, NutritionSource.AFCD, "Soft drink, cola flavour", FoodUnit.MILLILITRES)
        assertEquals(FoodUnit.MILLILITRES, EditableFoodItem(1, cola).current!!.unit)
        assertEquals(FoodUnit.GRAMS, EditableFoodItem(1, cola, unit = FoodUnit.GRAMS).current!!.unit)
        assertEquals(FoodUnit.GRAMS, EditableFoodItem(1, spaghetti).unit)
    }
}
