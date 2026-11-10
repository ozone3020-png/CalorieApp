package com.calorieapp.ui.settings

import com.calorieapp.data.EnergyUnit
import com.calorieapp.data.Goals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalFieldsTest {
    @Test fun `goals show in the chosen unit and parse back to kcal`() {
        val kj = GoalFields.from(Goals.AU_REFERENCE, EnergyUnit.KJ)
        assertEquals("8700", kj.energy)
        assertEquals(Goals.AU_REFERENCE.kcal, kj.parse(EnergyUnit.KJ)!!.kcal, 1e-9)
        assertEquals("2079", GoalFields.from(Goals.AU_REFERENCE, EnergyUnit.KCAL).energy)
    }

    @Test fun `invalid or empty fields make the goals invalid`() {
        val ok = GoalFields("2000", "120", "200", "60")
        assertEquals(120.0, ok.parse(EnergyUnit.KCAL)!!.proteinG, 1e-9)
        assertNull(ok.copy(protein = "").parse(EnergyUnit.KCAL))
        assertNull(ok.copy(energy = "0").parse(EnergyUnit.KCAL))
        assertNull(ok.copy(fat = "abc").parse(EnergyUnit.KCAL))
        assertEquals(62.5, ok.copy(fat = "62,5").parse(EnergyUnit.KCAL)!!.fatG, 1e-9)
    }

    @Test fun `fibre and fruit-veg goals default to Australian guidance and must be positive`() {
        val g = GoalFields.from(Goals.AU_REFERENCE, EnergyUnit.KCAL).parse(EnergyUnit.KCAL)!!
        assertEquals(30.0, g.fibreG, 1e-9)
        assertEquals(2.0, g.fruitServes, 1e-9)
        assertEquals(5.0, g.vegServes, 1e-9)
        val ok = GoalFields("2000", "120", "200", "60")
        assertEquals(7.5, ok.copy(veg = "7,5").parse(EnergyUnit.KCAL)!!.vegServes, 1e-9)
        assertNull(ok.copy(fibre = "").parse(EnergyUnit.KCAL))
    }
}
