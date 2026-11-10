package com.calorieapp.data

import com.calorieapp.data.local.FavouriteFoodEntity
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

/** One day in a trend: its totals, and whether anything was logged at all. */
data class DayTotals(val day: LocalDate, val logged: Boolean, val totals: NutritionTotals)

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
        return dao.insertMealWithEntries(meal, items.map { it.toEntry() })
    }

    /** Replaces the foods of an already-saved meal (it keeps its time and day). */
    suspend fun replaceMeal(mealId: Long, items: List<FoodItem>) {
        require(items.isNotEmpty()) { "A meal needs at least one item" }
        dao.replaceEntries(mealId, items.map { it.toEntry() })
    }

    /** Totals for every day from [from] to [to] (inclusive), with zeros for days nothing was logged. */
    fun observeDailyTotals(from: LocalDate, to: LocalDate): Flow<List<DayTotals>> =
        dao.observeDailyTotals(from.toEpochDay(), to.toEpochDay()).map { rows ->
            val byDay = rows.associateBy { it.epochDay }
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { day ->
                val r = byDay[day.toEpochDay()]
                DayTotals(
                    day = day,
                    logged = r != null,
                    totals = if (r == null) NutritionTotals() else NutritionTotals(r.kcal, r.proteinG, r.carbsG, r.fatG),
                )
            }.toList()
        }

    /** The latest version of each food logged recently, newest first. */
    fun observeRecentFoods(limit: Int = 30): Flow<List<FoodItem>> =
        dao.observeRecentFoods(limit).map { list -> list.map { it.toFoodItem() } }

    fun observeFavourites(): Flow<List<FoodItem>> =
        dao.observeFavourites().map { list -> list.map { it.toFoodItem() } }

    /** Stars or un-stars a food (by name), remembering its amount and nutrition for one-tap logging. */
    suspend fun setFavourite(item: FoodItem, favourite: Boolean) {
        if (favourite) {
            dao.insertFavourite(
                FavouriteFoodEntity(
                    name = item.name.trim(),
                    grams = item.grams,
                    unit = item.unit.wire,
                    calories = item.calories,
                    proteinG = item.proteinG,
                    carbsG = item.carbsG,
                    fatG = item.fatG,
                    source = item.source.wireOrNull(),
                    matchName = item.match,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            dao.deleteFavourite(item.name.trim())
        }
    }

    /** Meals logged on [day], newest first; updates live as meals are added or deleted. */
    fun observeDay(day: LocalDate): Flow<List<LoggedMeal>> =
        dao.observeDay(day.toEpochDay()).map { meals -> meals.map { it.toLoggedMeal() } }

    suspend fun deleteMeal(mealId: Long): DeletedFromLog? {
        val meal = dao.getMeal(mealId) ?: return null
        dao.deleteMeal(meal.meal)
        return DeletedFromLog(meal.meal, meal.entries)
    }

    /** Deletes one food; if it was the meal's only food, the (now empty) meal goes too. */
    suspend fun deleteFood(food: LoggedFood): DeletedFromLog? {
        val meal = dao.getMeal(food.mealId) ?: return null
        val entry = meal.entries.firstOrNull { it.id == food.id } ?: return null
        return if (meal.entries.size == 1) {
            dao.deleteMeal(meal.meal)
            DeletedFromLog(meal.meal, listOf(entry))
        } else {
            dao.deleteEntry(entry)
            DeletedFromLog(null, listOf(entry))
        }
    }

    suspend fun restore(deleted: DeletedFromLog) = dao.restore(deleted.meal, deleted.entries)
}

private fun MealWithEntries.toLoggedMeal() = LoggedMeal(
    id = meal.id,
    loggedAt = Instant.ofEpochMilli(meal.loggedAt),
    foods = entries.sortedBy { it.id }.map { e -> LoggedFood(id = e.id, mealId = e.mealId, item = e.toFoodItem()) },
)

private fun NutritionSource.wireOrNull(): String? = if (this == NutritionSource.UNKNOWN) null else name.lowercase()

private fun FoodItem.toEntry() = FoodEntryEntity(
    mealId = 0, // filled in by the DAO
    name = name,
    grams = grams,
    calories = calories,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    source = source.wireOrNull(),
    matchName = match,
    unit = unit.wire,
)

private fun FoodEntryEntity.toFoodItem() = FoodItem(
    name = name,
    grams = grams,
    calories = calories,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    source = source?.let(NutritionSource::from) ?: NutritionSource.UNKNOWN,
    match = matchName,
    unit = FoodUnit.from(unit),
)

private fun FavouriteFoodEntity.toFoodItem() = FoodItem(
    name = name,
    grams = grams,
    calories = calories,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    source = source?.let(NutritionSource::from) ?: NutritionSource.UNKNOWN,
    match = matchName,
    unit = FoodUnit.from(unit),
)
