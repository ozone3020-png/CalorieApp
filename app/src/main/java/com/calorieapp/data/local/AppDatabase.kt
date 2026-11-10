package com.calorieapp.data.local

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * One saved meal. [epochDay] is the local calendar day it was logged on, for daily totals.
 * [uuid] identifies it across devices for sync (schema v5); [updatedAt] is when it last changed here.
 */
@Entity(tableName = "meals", indices = [Index("epochDay"), Index(value = ["uuid"], unique = true)])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loggedAt: Long, // epoch millis
    val epochDay: Long,
    @ColumnInfo(defaultValue = "") val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

/** Records a deletion so it can be synced to other devices (schema v5). [syncId]: meal uuid / favourite key. */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "syncId"])
data class TombstoneEntity(val kind: String, val syncId: String, val deletedAt: Long)

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
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
) {
    /** How a favourite is identified for sync: its name, case- and space-insensitive. */
    val syncId get() = favouriteSyncId(name)
}

fun favouriteSyncId(name: String) = name.trim().lowercase()

/** One day's totals (query result). */
data class DayTotalsRow(
    val epochDay: Long,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
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
    protected abstract suspend fun deleteMealRow(mealId: Long)

    @Query("DELETE FROM food_entries WHERE id = :entryId")
    protected abstract suspend fun deleteEntryRow(entryId: Long)

    @Query("UPDATE meals SET updatedAt = :at WHERE id = :mealId")
    protected abstract suspend fun touchMeal(mealId: Long, at: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTombstone(tombstone: TombstoneEntity)

    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND syncId = :syncId")
    protected abstract suspend fun deleteTombstone(kind: String, syncId: String)

    /** Deletes a meal (and its foods), remembering the deletion for sync. */
    @Transaction
    open suspend fun deleteMeal(meal: MealEntity) {
        deleteMealRow(meal.id)
        insertTombstone(TombstoneEntity(KIND_MEAL, meal.uuid, System.currentTimeMillis()))
    }

    /** Deletes one food from a meal; the meal counts as changed. */
    @Transaction
    open suspend fun deleteEntry(entry: FoodEntryEntity) {
        deleteEntryRow(entry.id)
        touchMeal(entry.mealId)
    }

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
    protected abstract suspend fun insertFavouriteRow(food: FavouriteFoodEntity)

    @Query("DELETE FROM favourite_foods WHERE lower(trim(name)) = :syncId")
    protected abstract suspend fun deleteFavouriteRow(syncId: String)

    @Transaction
    open suspend fun insertFavourite(food: FavouriteFoodEntity) {
        deleteFavouriteRow(food.syncId) // replace any differently-cased duplicate
        insertFavouriteRow(food)
        deleteTombstone(KIND_FAVOURITE, food.syncId)
    }

    @Transaction
    open suspend fun deleteFavourite(name: String) {
        deleteFavouriteRow(favouriteSyncId(name))
        insertTombstone(TombstoneEntity(KIND_FAVOURITE, favouriteSyncId(name), System.currentTimeMillis()))
    }

    @Query("SELECT COUNT(*) FROM favourite_foods WHERE lower(name) = lower(:name)")
    abstract suspend fun isFavourite(name: String): Int

    @Query("DELETE FROM food_entries WHERE mealId = :mealId")
    protected abstract suspend fun deleteEntriesOf(mealId: Long)

    /** Replaces a saved meal's foods (keeping its time); used when editing a meal from the log. */
    @Transaction
    open suspend fun replaceEntries(mealId: Long, entries: List<FoodEntryEntity>) {
        deleteEntriesOf(mealId)
        insertEntries(entries.map { it.copy(id = 0, mealId = mealId) })
        touchMeal(mealId)
    }

    @Transaction
    @Query("SELECT * FROM meals ORDER BY loggedAt")
    abstract suspend fun getAllMeals(): List<MealWithEntries>

    /** Totals per logged day between two days (inclusive); days with nothing logged are absent. */
    @Query(
        """
        SELECT m.epochDay AS epochDay, SUM(e.calories) AS kcal, SUM(e.proteinG) AS proteinG,
               SUM(e.carbsG) AS carbsG, SUM(e.fatG) AS fatG
        FROM meals m JOIN food_entries e ON e.mealId = m.id
        WHERE m.epochDay BETWEEN :fromDay AND :toDay
        GROUP BY m.epochDay ORDER BY m.epochDay
        """,
    )
    abstract fun observeDailyTotals(fromDay: Long, toDay: Long): Flow<List<DayTotalsRow>>

    @Query("SELECT * FROM favourite_foods ORDER BY lower(name)")
    abstract suspend fun getFavourites(): List<FavouriteFoodEntity>

    /** Whether a meal logged at exactly this time already exists (to skip duplicates when restoring). */
    @Query("SELECT COUNT(*) FROM meals WHERE loggedAt = :loggedAt")
    abstract suspend fun countMealsAt(loggedAt: Long): Int

    /** Puts back a deleted meal and/or foods with their original ids (for Undo). */
    @Transaction
    open suspend fun restore(meal: MealEntity?, entries: List<FoodEntryEntity>) {
        if (meal != null) {
            insertMeal(meal.copy(updatedAt = System.currentTimeMillis()))
            deleteTombstone(KIND_MEAL, meal.uuid)
        }
        insertEntries(entries)
        entries.firstOrNull()?.let { touchMeal(it.mealId) }
    }

    // ---- Sync: what changed here, and applying changes from other devices (keeping their timestamps) ----

    @Transaction
    @Query("SELECT * FROM meals WHERE updatedAt > :since")
    abstract suspend fun mealsChangedSince(since: Long): List<MealWithEntries>

    @Query("SELECT * FROM favourite_foods WHERE updatedAt > :since")
    abstract suspend fun favouritesChangedSince(since: Long): List<FavouriteFoodEntity>

    @Query("SELECT * FROM sync_tombstones WHERE deletedAt > :since")
    abstract suspend fun tombstonesSince(since: Long): List<TombstoneEntity>

    @Transaction
    @Query("SELECT * FROM meals WHERE uuid = :uuid")
    abstract suspend fun getMealByUuid(uuid: String): MealWithEntries?

    @Query("SELECT * FROM favourite_foods WHERE lower(trim(name)) = :syncId")
    abstract suspend fun getFavouriteBySyncId(syncId: String): FavouriteFoodEntity?

    @Query("SELECT * FROM sync_tombstones WHERE kind = :kind AND syncId = :syncId")
    protected abstract suspend fun getTombstone(kind: String, syncId: String): TombstoneEntity?

    @Query("UPDATE meals SET loggedAt = :loggedAt, epochDay = :epochDay, updatedAt = :updatedAt WHERE id = :mealId")
    protected abstract suspend fun updateMealRow(mealId: Long, loggedAt: Long, epochDay: Long, updatedAt: Long)

    /** Applies a meal from another device if it's newer than ours (and wasn't deleted here after that). */
    @Transaction
    open suspend fun applyRemoteMeal(meal: MealEntity, entries: List<FoodEntryEntity>) {
        val local = getMealByUuid(meal.uuid)
        if (local != null && local.meal.updatedAt >= meal.updatedAt) return
        val tombstone = getTombstone(KIND_MEAL, meal.uuid)
        if (tombstone != null && tombstone.deletedAt >= meal.updatedAt) return
        val mealId = if (local != null) {
            updateMealRow(local.meal.id, meal.loggedAt, meal.epochDay, meal.updatedAt)
            deleteEntriesOf(local.meal.id)
            local.meal.id
        } else {
            insertMeal(meal.copy(id = 0))
        }
        insertEntries(entries.map { it.copy(id = 0, mealId = mealId) })
        deleteTombstone(KIND_MEAL, meal.uuid)
    }

    /** Applies a deletion from another device unless the meal was edited here after it. */
    @Transaction
    open suspend fun applyRemoteMealDeletion(uuid: String, deletedAt: Long) {
        val local = getMealByUuid(uuid) ?: return
        if (local.meal.updatedAt <= deletedAt) deleteMealRow(local.meal.id)
    }

    @Transaction
    open suspend fun applyRemoteFavourite(food: FavouriteFoodEntity) {
        val local = getFavouriteBySyncId(food.syncId)
        if (local != null && local.updatedAt >= food.updatedAt) return
        val tombstone = getTombstone(KIND_FAVOURITE, food.syncId)
        if (tombstone != null && tombstone.deletedAt >= food.updatedAt) return
        deleteFavouriteRow(food.syncId)
        insertFavouriteRow(food.copy(id = 0))
        deleteTombstone(KIND_FAVOURITE, food.syncId)
    }

    @Transaction
    open suspend fun applyRemoteFavouriteDeletion(syncId: String, deletedAt: Long) {
        val local = getFavouriteBySyncId(syncId) ?: return
        if (local.updatedAt <= deletedAt) deleteFavouriteRow(syncId)
    }

    /** Emits whenever anything syncable changes locally (used to trigger a sync shortly after). */
    @Query(
        """
        SELECT (SELECT IFNULL(MAX(updatedAt), 0) FROM meals) + (SELECT COUNT(*) FROM meals)
             + (SELECT IFNULL(MAX(updatedAt), 0) FROM favourite_foods) + (SELECT COUNT(*) FROM favourite_foods)
             + (SELECT IFNULL(MAX(deletedAt), 0) FROM sync_tombstones)
        """,
    )
    abstract fun observeChangeMarker(): Flow<Long>

    companion object {
        const val KIND_MEAL = "meal"
        const val KIND_FAVOURITE = "favourite"
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
    entities = [MealEntity::class, FoodEntryEntity::class, FavouriteFoodEntity::class, TombstoneEntity::class],
    version = 5,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // v2: food_entries.source, matchName
        AutoMigration(from = 2, to = 3), // v3: food_entries.unit
        AutoMigration(from = 3, to = 4), // v4: favourite_foods
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    companion object {
        /**
         * v5: sync ids and change times. Manual, because existing meals each need their own random id.
         * Room checks the result against the v5 schema when the database opens.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE meals ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE meals SET uuid = lower(hex(randomblob(16))), updatedAt = loggedAt")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_meals_uuid ON meals (uuid)")
                db.execSQL("ALTER TABLE favourite_foods ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE favourite_foods SET updatedAt = addedAt")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_tombstones (kind TEXT NOT NULL, syncId TEXT NOT NULL, " +
                        "deletedAt INTEGER NOT NULL, PRIMARY KEY(kind, syncId))",
                )
            }
        }
    }
}
