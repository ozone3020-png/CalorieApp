package com.calorieapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FruitVegTest {
    private fun food(grams: Double, group: PlantGroup?, fibre: Double = 0.0) =
        FoodItem("x", grams, 100.0, 1.0, 10.0, 1.0, fibreG = fibre, plantGroup = group)

    @Test fun `serves follow the Australian Dietary Guidelines serve sizes`() {
        assertEquals(1.0, food(150.0, PlantGroup.FRUIT).serves, 1e-9)
        assertEquals(1.0, food(30.0, PlantGroup.DRIED_FRUIT).serves, 1e-9)
        assertEquals(2.0, food(150.0, PlantGroup.VEG).serves, 1e-9)
        assertEquals(0.0, food(150.0, null).serves, 1e-9)
    }

    @Test fun `totals add fibre and split fruit (incl dried) from veg`() {
        val t = listOf(
            food(150.0, PlantGroup.FRUIT, fibre = 3.0),
            food(30.0, PlantGroup.DRIED_FRUIT, fibre = 1.0),
            food(225.0, PlantGroup.VEG, fibre = 6.0),
            food(200.0, null, fibre = 2.0),
        ).totals()
        assertEquals(12.0, t.fibreG, 1e-9)
        assertEquals(2.0, t.fruitServes, 1e-9)
        assertEquals(3.0, t.vegServes, 1e-9)
    }

    @Test fun `scaling a food scales its fibre and keeps its group`() {
        val half = food(150.0, PlantGroup.VEG, fibre = 4.0).scaledTo(75.0)
        assertEquals(2.0, half.fibreG, 1e-9)
        assertEquals(1.0, half.serves, 1e-9)
    }
}
