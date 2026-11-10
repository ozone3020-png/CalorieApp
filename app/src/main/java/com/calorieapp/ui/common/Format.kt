package com.calorieapp.ui.common

/** Whole numbers without a decimal ("150"), otherwise one decimal place ("4.5"). */
fun Double.fmt(): String =
    if (this % 1.0 == 0.0) toLong().toString() else String.format("%.1f", this)

/** Rounded to the nearest whole number, for calories. */
fun Double.fmtWhole(): String = Math.round(this).toString()
