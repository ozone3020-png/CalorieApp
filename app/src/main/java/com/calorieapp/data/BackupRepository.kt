package com.calorieapp.data

import android.content.ContentResolver
import android.net.Uri
import androidx.room.withTransaction
import com.calorieapp.data.local.AppDatabase
import com.calorieapp.data.local.FavouriteFoodEntity
import com.calorieapp.data.local.FoodEntryEntity
import com.calorieapp.data.local.MealEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything needed to rebuild the log on another install. Versioned so old backups stay readable. */
@Serializable
data class Backup(
    val app: String = "CalorieApp",
    val version: Int = 1,
    val exportedAt: Long,
    val settings: BackupSettings? = null,
    val meals: List<BackupMeal> = emptyList(),
    val favourites: List<BackupFood> = emptyList(),
)

@Serializable
data class BackupSettings(
    val energyUnit: String,
    val goalKcal: Double,
    val goalProteinG: Double,
    val goalCarbsG: Double,
    val goalFatG: Double,
    val aiProvider: String,
)

@Serializable
data class BackupMeal(val loggedAt: Long, val epochDay: Long, val foods: List<BackupFood>)

@Serializable
data class BackupFood(
    val name: String,
    val grams: Double,
    val unit: String = "g",
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val source: String? = null,
    val matchName: String? = null,
)

data class RestoreResult(val mealsAdded: Int, val mealsSkipped: Int, val favourites: Int, val settingsRestored: Boolean)

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Backup / restore (JSON) and spreadsheet export (CSV) of the whole log, via files the user picks. */
class BackupRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val resolver: ContentResolver,
) {
    private val dao = db.mealDao()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    suspend fun createBackup(): Backup {
        val s = settings.settings.first()
        return Backup(
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                energyUnit = s.energyUnit.name,
                goalKcal = s.goals.kcal,
                goalProteinG = s.goals.proteinG,
                goalCarbsG = s.goals.carbsG,
                goalFatG = s.goals.fatG,
                aiProvider = s.aiProvider.wire,
            ),
            meals = dao.getAllMeals().map { m ->
                BackupMeal(m.meal.loggedAt, m.meal.epochDay, m.entries.sortedBy { it.id }.map { it.toBackup() })
            },
            favourites = dao.getFavourites().map { it.toBackup() },
        )
    }

    /** Writes a full backup to [uri]; returns the number of meals in it. */
    suspend fun writeBackup(uri: Uri): Int {
        val backup = createBackup()
        write(uri, json.encodeToString(Backup.serializer(), backup))
        return backup.meals.size
    }

    /**
     * Adds the meals and favourites from a backup file. Meals already in the log (same logged time) are skipped,
     * so restoring twice, or into a log that already has some of them, doesn't create duplicates.
     */
    suspend fun restore(uri: Uri): RestoreResult {
        val text = withContext(Dispatchers.IO) {
            try {
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: IOException) {
                throw BackupException("Couldn't read that file.", e)
            } catch (e: SecurityException) {
                throw BackupException("Couldn't read that file.", e)
            }
        } ?: throw BackupException("Couldn't read that file.")
        val backup = try {
            json.decodeFromString(Backup.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException("That isn't a Calorie App backup file.", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException("That isn't a Calorie App backup file.", e)
        }
        if (backup.app != "CalorieApp") throw BackupException("That isn't a Calorie App backup file.")
        if (backup.version > 1) throw BackupException("This backup is from a newer version of the app. Update the app first.")

        var added = 0
        var skipped = 0
        db.withTransaction {
            for (meal in backup.meals) {
                if (meal.foods.isEmpty() || dao.countMealsAt(meal.loggedAt) > 0) {
                    skipped++
                    continue
                }
                dao.insertMealWithEntries(
                    MealEntity(loggedAt = meal.loggedAt, epochDay = meal.epochDay),
                    meal.foods.map { it.toEntry() },
                )
                added++
            }
            for (fav in backup.favourites) dao.insertFavourite(fav.toFavourite())
        }
        val restoredSettings = backup.settings?.let { s ->
            settings.setEnergyUnit(EnergyUnit.entries.firstOrNull { it.name == s.energyUnit } ?: EnergyUnit.KCAL)
            settings.setGoals(Goals(s.goalKcal, s.goalProteinG, s.goalCarbsG, s.goalFatG))
            AiProvider.from(s.aiProvider)?.let { settings.setAiProvider(it) }
            true
        } ?: false
        return RestoreResult(added, skipped, backup.favourites.size, restoredSettings)
    }

    /** Writes every logged food as one CSV row (for spreadsheets); returns the number of rows. */
    suspend fun writeCsv(uri: Uri): Int {
        val zone = ZoneId.systemDefault()
        val date = DateTimeFormatter.ISO_LOCAL_DATE
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val rows = mutableListOf(
            listOf(
                "date", "time", "meal_id", "food", "amount", "unit", "energy_kcal", "energy_kj",
                "protein_g", "carbs_g", "fat_g", "source", "database_match",
            ),
        )
        for (m in dao.getAllMeals()) {
            val at = Instant.ofEpochMilli(m.meal.loggedAt).atZone(zone)
            for (e in m.entries.sortedBy { it.id }) {
                rows += listOf(
                    at.format(date), at.format(time), m.meal.id.toString(), e.name, num(e.grams), e.unit,
                    num(e.calories), num(e.calories * EnergyUnit.KJ_PER_KCAL), num(e.proteinG), num(e.carbsG),
                    num(e.fatG), e.source.orEmpty(), e.matchName.orEmpty(),
                )
            }
        }
        write(uri, rows.joinToString("\r\n") { row -> row.joinToString(",") { csvField(it) } } + "\r\n")
        return rows.size - 1
    }

    private suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        try {
            val out = resolver.openOutputStream(uri, "wt") ?: throw BackupException("Couldn't write to that file.")
            out.bufferedWriter().use { it.write(text) }
        } catch (e: IOException) {
            throw BackupException("Couldn't write to that file.", e)
        } catch (e: SecurityException) {
            throw BackupException("Couldn't write to that file.", e)
        }
    }
}

internal fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

private fun num(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

private fun FoodEntryEntity.toBackup() =
    BackupFood(name, grams, unit, calories, proteinG, carbsG, fatG, source, matchName)

private fun FavouriteFoodEntity.toBackup() =
    BackupFood(name, grams, unit, calories, proteinG, carbsG, fatG, source, matchName)

private fun BackupFood.toEntry() = FoodEntryEntity(
    mealId = 0, name = name, grams = grams, calories = calories, proteinG = proteinG, carbsG = carbsG,
    fatG = fatG, source = source, matchName = matchName, unit = unit,
)

private fun BackupFood.toFavourite() = FavouriteFoodEntity(
    name = name, grams = grams, unit = unit, calories = calories, proteinG = proteinG, carbsG = carbsG,
    fatG = fatG, source = source, matchName = matchName, addedAt = System.currentTimeMillis(),
)
