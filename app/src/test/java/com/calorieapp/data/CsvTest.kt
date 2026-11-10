package com.calorieapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test fun `plain fields are left alone`() = assertEquals("banana", csvField("banana"))

    @Test fun `commas, quotes and newlines are quoted and escaped`() {
        assertEquals("\"Rice, white, boiled\"", csvField("Rice, white, boiled"))
        assertEquals("\"5\"\" pizza\"", csvField("5\" pizza"))
        assertEquals("\"two\nlines\"", csvField("two\nlines"))
    }
}
