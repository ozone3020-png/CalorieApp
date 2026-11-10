package com.calorieapp.data

/** Where an item's nutrition numbers came from. */
enum class NutritionSource(val label: String) {
    AFCD("AFCD"),
    USDA("USDA"),
    AI("AI estimate"),

    /** Read from the product's nutrition information panel. */
    LABEL("Label"),

    /** Open Food Facts, looked up by barcode (crowd-sourced). */
    OFF("Open Food Facts"),

    /** Saved before sources were recorded (database v1). */
    UNKNOWN("");

    companion object {
        fun from(value: String?) = when (value?.lowercase()) {
            "afcd" -> AFCD
            "usda" -> USDA
            "label" -> LABEL
            "off" -> OFF
            else -> AI
        }
    }
}

/**
 * Australian Dietary Guidelines fruit and vegetable serves: a serve is 150 g of fruit, 30 g of dried fruit,
 * or 75 g of vegetables or legumes (adults aim for 2 fruit and 5 veg a day).
 */
enum class PlantGroup(val wire: String, val label: String, val serveGrams: Double) {
    FRUIT("fruit", "Fruit", 150.0),
    DRIED_FRUIT("dried_fruit", "Dried fruit", 30.0),
    VEG("veg", "Veg", 75.0);

    val isFruit get() = this != VEG

    companion object {
        fun from(wire: String?) = entries.firstOrNull { it.wire == wire }
    }
}

/** What a photo shows: a plate of food, or a product's nutrition information panel. */
enum class ScanMode(val wire: String) {
    MEAL("meal"),
    LABEL("label"),

    /** Not a photo: a scanned barcode looked up in Open Food Facts. */
    BARCODE("barcode"),
}

/** Unit of an item's amount. Drinks are measured in millilitres. */
enum class FoodUnit(val label: String, val wire: String) {
    GRAMS("g", "g"),
    MILLILITRES("mL", "ml");

    companion object {
        fun from(value: String?) = if (value?.lowercase() == "ml") MILLILITRES else GRAMS
    }
}

/**
 * One food detected in a photo. Nutrition values are for [grams] of the food, in [unit]
 * (the field is named grams for history; for drinks it holds millilitres).
 * [match] is the database entry the numbers came from (null for AI estimates).
 */
data class FoodItem(
    val name: String,
    val grams: Double,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val source: NutritionSource = NutritionSource.AI,
    val match: String? = null,
    val unit: FoodUnit = FoodUnit.GRAMS,
    /** Set when label numbers look inconsistent; shown to the user, not saved. */
    val warning: String? = null,
    val fibreG: Double = 0.0,
    /** Counts towards fruit or veg serves (null: neither). */
    val plantGroup: PlantGroup? = null,
) {
    /** Fruit or veg serves this amount is worth (0 if it's neither). */
    val serves: Double get() = plantGroup?.let { grams / it.serveGrams } ?: 0.0

    /**
     * The same food at a different portion size: nutrition scales linearly with weight.
     * If the original weight is unknown (0), nutrition is kept as-is.
     */
    fun scaledTo(newGrams: Double): FoodItem {
        if (grams <= 0.0) return copy(grams = newGrams)
        val f = newGrams / grams
        return copy(
            grams = newGrams,
            calories = calories * f,
            proteinG = proteinG * f,
            carbsG = carbsG * f,
            fatG = fatG * f,
            fibreG = fibreG * f,
        )
    }
}

data class NutritionTotals(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fibreG: Double = 0.0,
    val fruitServes: Double = 0.0,
    val vegServes: Double = 0.0,
)

fun List<FoodItem>.totals() = NutritionTotals(
    calories = sumOf { it.calories },
    proteinG = sumOf { it.proteinG },
    carbsG = sumOf { it.carbsG },
    fatG = sumOf { it.fatG },
    fibreG = sumOf { it.fibreG },
    fruitServes = filter { it.plantGroup?.isFruit == true }.sumOf { it.serves },
    vegServes = filter { it.plantGroup == PlantGroup.VEG }.sumOf { it.serves },
)
