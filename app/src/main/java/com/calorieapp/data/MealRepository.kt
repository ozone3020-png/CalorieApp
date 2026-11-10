package com.calorieapp.data

import com.calorieapp.data.local.FoodEntryEntity
import com.calorieapp.data.local.MealDao
import com.calorieapp.data.local.MealEntity
import java.time.ZonedDateTime

class MealRepository(private val dao: MealDao) {

    /** Logs [items] as one meal on the local day of [at]. */
    suspend fun saveMeal(items: List<FoodItem>, at: ZonedDateTime = ZonedDateTime.now()): Long {
        require(items.isNotEmpty()) { "A meal needs at least one item" }
        val meal = MealEntity(
            loggedAt = at.toInstant().toEpochMilli(),
            epochDay = at.toLocalDate().toEpochDay(),
        )
        val entries = items.map {
            FoodEntryEntity(
                mealId = 0, // filled in by the DAO
                name = it.name,
                grams = it.grams,
                calories = it.calories,
                proteinG = it.proteinG,
                carbsG = it.carbsG,
                fatG = it.fatG,
                source = it.source.name.lowercase(),
                matchName = it.match,
                unit = it.unit.wire,
            )
        }
        return dao.insertMealWithEntries(meal, entries)
    }
}
