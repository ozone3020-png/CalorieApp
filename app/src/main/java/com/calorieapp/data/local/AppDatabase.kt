package com.calorieapp.data.local

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
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

@Dao
abstract class MealDao {
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
    entities = [MealEntity::class, FoodEntryEntity::class],
    version = 3,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // v2: food_entries.source, matchName
        AutoMigration(from = 2, to = 3), // v3: food_entries.unit
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
}
