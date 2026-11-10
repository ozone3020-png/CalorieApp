package com.calorieapp.data.local

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import kotlinx.coroutines.flow.Flow
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** One saved photo/meal. [epochDay] is the local calendar day it was logged on, for daily totals. */
@Entity(tableName = "meals", indices = [Index("epochDay")])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loggedAt: Long, // epoch millis
    val epochDay: Long,
)

/** One food within a meal, with nutrition for the (possibly user-edited) [grams]. */
@Entity(
    tableName = "food_entries",
    foreignKeys = [
        ForeignKey(
            entity = MealEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mealId")],
)
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val name: String,
    val grams: Double,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** "afcd", "usda" or "ai" (added in schema v2; null for meals saved before). */
    val source: String? = null,
    /** Database food the numbers came from, e.g. "Rice, white, boiled or rice cooker, no added salt". */
    val matchName: String? = null,
    /** "g" or "ml" (schema v3); [grams] holds the amount in this unit. */
    @ColumnInfo(defaultValue = "g") val unit: String = "g",
)

/** A food the user starred for one-tap logging (schema v4). Identified by name. */
@Entity(tableName = "favourite_foods", indices = [Index(value = ["name"], unique = true)])
data class FavouriteFoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val grams: Double,
    val unit: String,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val source: String?,
    val matchName: String?,
    val addedAt: Long,
)

/** A meal with its foods, loaded together. */
data class MealWithEntries(
    @Embedded val meal: MealEntity,
    @Relation(parentColumn = "id", entityColumn = "mealId")
    val entries: List<FoodEntryEntity>,
)

@Dao
abstract class MealDao {
    /** All meals logged on [epochDay], newest first; re-emits whenever the data changes. */
    @Transaction
    @Query("SELECT * FROM meals WHERE epochDay = :epochDay ORDER BY loggedAt DESC")
    abstract fun observeDay(epochDay: Long): Flow<List<MealWithEntries>>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :mealId")
    abstract suspend fun getMeal(mealId: Long): MealWithEntries?

    @Query("DELETE FROM meals WHERE id = :mealId") // food_entries go too (ON DELETE CASCADE)
    abstract suspend fun deleteMeal(mealId: Long)

    @Query("DELETE FROM food_entries WHERE id = :entryId")
    abstract suspend fun deleteEntry(entryId: Long)

    /**
     * The most recently added entry for each distinct food name (case-insensitive), most recently added
     * first (by entry id, not meal time, so a back-dated meal still counts as recent). For one-tap re-logging.
     */
    @Query(
        """
        SELECT e.* FROM food_entries e
        WHERE e.id IN (SELECT MAX(id) FROM food_entries GROUP BY lower(trim(name)))
        ORDER BY e.id DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecentFoods(limit: Int): Flow<List<FoodEntryEntity>>

    @Query("SELECT * FROM favourite_foods ORDER BY lower(name)")
    abstract fun observeFavourites(): Flow<List<FavouriteFoodEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFavourite(food: FavouriteFoodEntity)

    @Query("DELETE FROM favourite_foods WHERE lower(name) = lower(:name)")
    abstract suspend fun deleteFavourite(name: String)

    @Query("SELECT COUNT(*) FROM favourite_foods WHERE lower(name) = lower(:name)")
    abstract suspend fun isFavourite(name: String): Int

    @Query("DELETE FROM food_entries WHERE mealId = :mealId")
    protected abstract suspend fun deleteEntriesOf(mealId: Long)

    /** Replaces a saved meal's foods (keeping its time); used when editing a meal from the log. */
    @Transaction
    open suspend fun replaceEntries(mealId: Long, entries: List<FoodEntryEntity>) {
        deleteEntriesOf(mealId)
        insertEntries(entries.map { it.copy(id = 0, mealId = mealId) })
    }

    @Transaction
    @Query("SELECT * FROM meals ORDER BY loggedAt")
    abstract suspend fun getAllMeals(): List<MealWithEntries>

    @Query("SELECT * FROM favourite_foods ORDER BY lower(name)")
    abstract suspend fun getFavourites(): List<FavouriteFoodEntity>

    /** Whether a meal logged at exactly this time already exists (to skip duplicates when restoring). */
    @Query("SELECT COUNT(*) FROM meals WHERE loggedAt = :loggedAt")
    abstract suspend fun countMealsAt(loggedAt: Long): Int

    /** Puts back a deleted meal and/or foods with their original ids (for Undo). */
    @Transaction
    open suspend fun restore(meal: MealEntity?, entries: List<FoodEntryEntity>) {
        if (meal != null) insertMeal(meal)
        insertEntries(entries)
    }

    @Insert
    protected abstract suspend fun insertMeal(meal: MealEntity): Long

    @Insert
    protected abstract suspend fun insertEntries(entries: List<FoodEntryEntity>)

    /** Saves a meal and its foods atomically; returns the new meal id. */
    @Transaction
    open suspend fun insertMealWithEntries(meal: MealEntity, entries: List<FoodEntryEntity>): Long {
        val mealId = insertMeal(meal)
        insertEntries(entries.map { it.copy(mealId = mealId) })
        return mealId
    }
}

@Database(
    entities = [MealEntity::class, FoodEntryEntity::class, FavouriteFoodEntity::class],
    version = 4,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // v2: food_entries.source, matchName
        AutoMigration(from = 2, to = 3), // v3: food_entries.unit
        AutoMigration(from = 3, to = 4), // v4: favourite_foods
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
}
