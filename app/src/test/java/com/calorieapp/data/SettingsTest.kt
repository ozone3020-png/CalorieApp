package com.calorieapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {
    @Test fun `server URLs are normalised to end in a slash`() {
        assertEquals("https://a.workers.dev/", normalizeServerUrl("https://a.workers.dev"))
        assertEquals("https://a.workers.dev/", normalizeServerUrl("  https://a.workers.dev/ "))
        assertEquals("https://x.dev/api/", normalizeServerUrl("https://x.dev/api"))
    }

    @Test fun `only https, or http to the emulator host, is accepted`() {
        assertNull(normalizeServerUrl("http://a.workers.dev"))
        assertNull(normalizeServerUrl("a.workers.dev"))
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("not a url"))
        assertEquals("http://10.0.2.2:8787/", normalizeServerUrl("http://10.0.2.2:8787"))
    }

    @Test fun `energy converts between kcal and kJ`() {
        assertEquals(418.4, EnergyUnit.KJ.fromKcal(100.0), 1e-9)
        assertEquals(100.0, EnergyUnit.KJ.toKcal(418.4), 1e-9)
        assertEquals(100.0, EnergyUnit.KCAL.fromKcal(100.0), 1e-9)
    }

    @Test fun `Australian reference goals are 8700 kJ`() {
        assertEquals(8700.0, EnergyUnit.KJ.fromKcal(Goals.AU_REFERENCE.kcal), 1e-6)
    }
}
