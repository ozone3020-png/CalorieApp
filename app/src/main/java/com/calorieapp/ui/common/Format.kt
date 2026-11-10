package com.calorieapp.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.calorieapp.data.EnergyUnit
import kotlin.math.roundToLong

/** Whole numbers without a decimal ("150"), otherwise one decimal place ("4.5"). */
fun Double.fmt(): String =
    if (this % 1.0 == 0.0) toLong().toString() else String.format("%.1f", this)

/** Rounded to the nearest whole number. */
fun Double.fmtWhole(): String = roundToLong().toString()

/** The energy unit chosen in Settings, provided at the top of the UI. */
val LocalEnergyUnit = staticCompositionLocalOf { EnergyUnit.KCAL }

/** An energy amount given in kcal, shown in [unit] without the unit label: "512" or "2142". */
fun energyNumber(kcal: Double, unit: EnergyUnit): String = unit.fromKcal(kcal).fmtWhole()

/** An energy amount given in kcal, shown in [unit] with its label: "512 kcal" or "2142 kJ". */
fun energyText(kcal: Double, unit: EnergyUnit): String = "${energyNumber(kcal, unit)} ${unit.label}"
