package com.calorieapp.ui.meal

import com.calorieapp.data.FoodItem
import com.calorieapp.data.FoodUnit
import com.calorieapp.data.NutritionSource
import com.calorieapp.data.PlantGroup

/** Progress of fetching nutrition for a renamed item. */
sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Failed(val message: String) : LookupState
}

/**
 * A detected food as the user is editing it. [original] holds the nutrition for the name it was
 * last looked up under; changing the weight rescales from it rather than compounding edits, and
 * renaming triggers a fresh lookup that replaces it.
 */
data class EditableFoodItem(
    val key: Long,
    val original: FoodItem,
    val name: String = original.name,
    val gramsText: String = original.grams.toPlainString(),
    val lookup: LookupState = LookupState.Idle,
    val unit: FoodUnit = original.unit,
    /** Fruit / veg / neither; starts as the database's (or AI's) classification and can be changed. */
    val plantGroup: PlantGroup? = original.plantGroup,
) {
    /** Parsed weight, or null while the field is empty/invalid. Accepts "150", "150.5" and "150,5". */
    val grams: Double? = gramsText.trim().replace(',', '.').toDoubleOrNull()
        ?.takeIf { it > 0.0 && it <= MAX_GRAMS }

    val isNameValid get() = name.isNotBlank()
    val isGramsValid get() = grams != null
    val isValid get() = isNameValid && isGramsValid

    /**
     * True when the typed name differs from the food the nutrition belongs to. Label and barcode items never
     * need a lookup: their numbers belong to that exact product, whatever the user calls it.
     */
    val needsLookup
        get() = original.source != NutritionSource.LABEL && original.source != NutritionSource.OFF &&
            isNameValid && !name.trim().equals(original.name.trim(), ignoreCase = true)

    /** Weight to look a renamed food up at: the typed weight, else the original, else 100 g. */
    val lookupGrams get() = grams ?: original.grams.takeIf { it > 0.0 } ?: 100.0

    /** The item with the user's edits applied, or null if the weight is invalid. */
    val current: FoodItem? = grams?.let { original.scaledTo(it).copy(name = name.trim(), unit = unit, plantGroup = plantGroup) }

    companion object {
        const val MAX_GRAMS = 5_000.0
    }
}

private fun Double.toPlainString(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
