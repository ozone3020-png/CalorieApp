package com.calorieapp.data

import com.calorieapp.data.local.FoodEntryEntity
import com.calorieapp.data.local.MealDao
import com.calorieapp.data.local.MealEntity
import com.calorieapp.data.local.MealWithEntries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

/** A saved meal as shown in the daily log. */
data class LoggedMeal(
    val id: Long,
    val loggedAt: Instant,
    val foods: List<LoggedFood>,
) {
    val totals get() = foods.map { it.item }.totals()
}

data class LoggedFood(val id: Long, val mealId: Long, val item: FoodItem)

/** Snapshot of something deleted from the log, so it can be put back. */
class DeletedFromLog internal constructor(
    internal val meal: MealEntity?,
    internal val entries: List<FoodEntryEntity>,
)

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

    /** Meals logged on [day], newest first; updates live as meals are added or deleted. */
    fun observeDay(day: LocalDate): Flow<List<LoggedMeal>> =
        dao.observeDay(day.toEpochDay()).map { meals -> meals.map { it.toLoggedMeal() } }

    suspend fun deleteMeal(mealId: Long): DeletedFromLog? {
        val meal = dao.getMeal(mealId) ?: return null
        dao.deleteMeal(mealId)
        return DeletedFromLog(meal.meal, meal.entries)
    }

    /** Deletes one food; if it was the meal's only food, the (now empty) meal goes too. */
    suspend fun deleteFood(food: LoggedFood): DeletedFromLog? {
        val meal = dao.getMeal(food.mealId) ?: return null
        val entry = meal.entries.firstOrNull { it.id == food.id } ?: return null
        return if (meal.entries.size == 1) {
            dao.deleteMeal(meal.meal.id)
            DeletedFromLog(meal.meal, listOf(entry))
        } else {
            dao.deleteEntry(entry.id)
            DeletedFromLog(null, listOf(entry))
        }
    }

    suspend fun restore(deleted: DeletedFromLog) = dao.restore(deleted.meal, deleted.entries)
}

private fun MealWithEntries.toLoggedMeal() = LoggedMeal(
    id = meal.id,
    loggedAt = Instant.ofEpochMilli(meal.loggedAt),
    foods = entries.sortedBy { it.id }.map { e ->
        LoggedFood(
            id = e.id,
            mealId = e.mealId,
            item = FoodItem(
                name = e.name,
                grams = e.grams,
                calories = e.calories,
                proteinG = e.proteinG,
                carbsG = e.carbsG,
                fatG = e.fatG,
                source = e.source?.let(NutritionSource::from) ?: NutritionSource.UNKNOWN,
                match = e.matchName,
                unit = FoodUnit.from(e.unit),
            ),
        )
    },
)
