package com.calorieapp.data.sync

import com.calorieapp.data.BackupFood
import com.calorieapp.data.BackupMeal
import com.calorieapp.data.SettingsRepository
import com.calorieapp.data.local.FavouriteFoodEntity
import com.calorieapp.data.local.FoodEntryEntity
import com.calorieapp.data.local.MealDao
import com.calorieapp.data.local.MealDao.Companion.KIND_FAVOURITE
import com.calorieapp.data.local.MealDao.Companion.KIND_MEAL
import com.calorieapp.data.local.MealEntity
import com.calorieapp.data.remote.AnalyzeApi
import com.calorieapp.data.remote.SyncChange
import com.calorieapp.data.remote.SyncRecord
import com.calorieapp.data.remote.SyncRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.security.GeneralSecurityException

/** What the sync settings show. */
data class SyncStatus(val running: Boolean = false, val error: String? = null)

/**
 * Keeps this phone's log in step with the user's other phones through the Worker's /sync endpoint.
 * Changes go up encrypted; changes from elsewhere come down and are applied if newer (last write wins).
 */
class SyncRepository(
    private val dao: MealDao,
    private val settings: SettingsRepository,
    private val api: suspend () -> AnalyzeApi,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Starts syncing with a new key (null) or an existing one from another phone, then syncs straight away. */
    suspend fun enable(existingKey: String? = null): Boolean {
        val key = existingKey?.trim() ?: SyncCrypto.newKey()
        require(SyncCrypto.isValidKey(key)) { "Not a valid sync key" }
        settings.setSyncKey(key)
        return syncNow()
    }

    /** Stops syncing on this phone. The log stays here; the synced copy stays for the other phones. */
    suspend fun disable() {
        mutex.withLock { settings.setSyncKey(null) }
        _status.value = SyncStatus()
    }

    /** Sends local changes and applies remote ones. Returns false (with [status] explaining) if it failed. */
    suspend fun syncNow(): Boolean = mutex.withLock {
        val prefs = settings.settings.first().sync ?: return true
        _status.value = SyncStatus(running = true)
        try {
            val crypto = SyncCrypto(prefs.key)
            val startedAt = System.currentTimeMillis()
            var pending = localChanges(prefs.lastPushAt, crypto)
            var rev = prefs.lastRev
            val api = api()
            do {
                val batch = pending.take(BATCH)
                pending = pending.drop(BATCH)
                val response = api.sync(SyncRequest(account = crypto.accountId, since = rev, changes = batch))
                response.records.forEach { apply(it, crypto) }
                rev = response.rev
            } while (response.more || pending.isNotEmpty())
            settings.setSyncProgress(lastRev = rev, lastPushAt = startedAt, syncedAt = System.currentTimeMillis())
            _status.value = SyncStatus()
            true
        } catch (e: HttpException) {
            fail(if (e.code() == 501) "This server doesn't support sync." else "Sync failed (server error ${e.code()}).")
        } catch (e: IOException) {
            fail("Couldn't reach the server. Sync will try again later.")
        } catch (e: SerializationException) {
            fail("Sync failed: unexpected reply from the server.")
        } catch (e: GeneralSecurityException) {
            fail("Couldn't decrypt synced data. Check the sync key matches your other phone.")
        } catch (e: IllegalArgumentException) {
            fail("Couldn't decrypt synced data. Check the sync key matches your other phone.")
        }
    }

    private fun fail(message: String): Boolean {
        _status.value = SyncStatus(error = message)
        return false
    }

    /** Everything changed or deleted here since [since], encrypted for upload. */
    private suspend fun localChanges(since: Long, crypto: SyncCrypto): List<SyncChange> {
        val meals = dao.mealsChangedSince(since).map { m ->
            val payload = BackupMeal(
                loggedAt = m.meal.loggedAt,
                epochDay = m.meal.epochDay,
                foods = m.entries.sortedBy { it.id }.map { it.toBackupFood() },
            )
            SyncChange(
                kind = KIND_MEAL,
                id = m.meal.uuid,
                updatedAt = m.meal.updatedAt,
                data = crypto.encrypt(json.encodeToString(BackupMeal.serializer(), payload), KIND_MEAL, m.meal.uuid),
            )
        }
        val favourites = dao.favouritesChangedSince(since).map { f ->
            val payload = BackupFood(f.name, f.grams, f.unit, f.calories, f.proteinG, f.carbsG, f.fatG, f.source, f.matchName)
            SyncChange(
                kind = KIND_FAVOURITE,
                id = f.syncId,
                updatedAt = f.updatedAt,
                data = crypto.encrypt(json.encodeToString(BackupFood.serializer(), payload), KIND_FAVOURITE, f.syncId),
            )
        }
        val deletions = dao.tombstonesSince(since).map {
            SyncChange(kind = it.kind, id = it.syncId, updatedAt = it.deletedAt, deleted = true)
        }
        return meals + favourites + deletions
    }

    private suspend fun apply(record: SyncRecord, crypto: SyncCrypto) {
        when (record.kind) {
            KIND_MEAL -> if (record.deleted) {
                dao.applyRemoteMealDeletion(record.id, record.updatedAt)
            } else {
                val meal = json.decodeFromString(
                    BackupMeal.serializer(),
                    crypto.decrypt(record.data ?: return, KIND_MEAL, record.id),
                )
                dao.applyRemoteMeal(
                    MealEntity(loggedAt = meal.loggedAt, epochDay = meal.epochDay, uuid = record.id, updatedAt = record.updatedAt),
                    meal.foods.map { it.toEntry() },
                )
            }
            KIND_FAVOURITE -> if (record.deleted) {
                dao.applyRemoteFavouriteDeletion(record.id, record.updatedAt)
            } else {
                val f = json.decodeFromString(
                    BackupFood.serializer(),
                    crypto.decrypt(record.data ?: return, KIND_FAVOURITE, record.id),
                )
                dao.applyRemoteFavourite(
                    FavouriteFoodEntity(
                        name = f.name, grams = f.grams, unit = f.unit, calories = f.calories, proteinG = f.proteinG,
                        carbsG = f.carbsG, fatG = f.fatG, source = f.source, matchName = f.matchName,
                        addedAt = record.updatedAt, updatedAt = record.updatedAt,
                    ),
                )
            }
        }
    }

    private companion object {
        const val BATCH = 200
    }
}

private fun FoodEntryEntity.toBackupFood() = BackupFood(name, grams, unit, calories, proteinG, carbsG, fatG, source, matchName)

private fun BackupFood.toEntry() = FoodEntryEntity(
    mealId = 0, name = name, grams = grams, calories = calories, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
    source = source, matchName = matchName, unit = unit,
)
