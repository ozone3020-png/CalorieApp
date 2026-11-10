package com.calorieapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How energy is displayed. Stored values are always kcal. */
enum class EnergyUnit(val label: String) {
    KCAL("kcal"),
    KJ("kJ");

    fun fromKcal(kcal: Double) = if (this == KJ) kcal * KJ_PER_KCAL else kcal
    fun toKcal(value: Double) = if (this == KJ) value / KJ_PER_KCAL else value

    companion object {
        const val KJ_PER_KCAL = 4.184
    }
}

/** Daily targets. Energy in kcal. */
data class Goals(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
) {
    companion object {
        /**
         * The reference values Australian labels use for "% daily intake" (average adult):
         * 8700 kJ, 50 g protein, 310 g carbohydrate, 70 g fat.
         */
        val AU_REFERENCE = Goals(kcal = 8700 / EnergyUnit.KJ_PER_KCAL, proteinG = 50.0, carbsG = 310.0, fatG = 70.0)
    }
}

data class UserSettings(
    val energyUnit: EnergyUnit = EnergyUnit.KCAL,
    val goals: Goals = Goals.AU_REFERENCE,
    /** Overrides the built-in Worker URL when set (e.g. a friend's own Worker). */
    val serverUrl: String? = null,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<UserSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun setEnergyUnit(unit: EnergyUnit) = context.dataStore.edit { it[ENERGY_UNIT] = unit.name }

    suspend fun setGoals(goals: Goals) = context.dataStore.edit {
        it[GOAL_KCAL] = goals.kcal
        it[GOAL_PROTEIN] = goals.proteinG
        it[GOAL_CARBS] = goals.carbsG
        it[GOAL_FAT] = goals.fatG
    }

    /** Saves a custom server URL, or clears it (back to the built-in one) when null/blank. */
    suspend fun setServerUrl(url: String?) = context.dataStore.edit {
        val normalized = url?.let(::normalizeServerUrl)
        if (normalized == null) it.remove(SERVER_URL) else it[SERVER_URL] = normalized
    }

    private fun Preferences.toSettings(): UserSettings {
        val defaults = Goals.AU_REFERENCE
        return UserSettings(
            energyUnit = this[ENERGY_UNIT]?.let { name -> EnergyUnit.entries.firstOrNull { it.name == name } }
                ?: EnergyUnit.KCAL,
            goals = Goals(
                kcal = this[GOAL_KCAL] ?: defaults.kcal,
                proteinG = this[GOAL_PROTEIN] ?: defaults.proteinG,
                carbsG = this[GOAL_CARBS] ?: defaults.carbsG,
                fatG = this[GOAL_FAT] ?: defaults.fatG,
            ),
            serverUrl = this[SERVER_URL],
        )
    }

    private companion object {
        val ENERGY_UNIT = stringPreferencesKey("energy_unit")
        val GOAL_KCAL = doublePreferencesKey("goal_kcal")
        val GOAL_PROTEIN = doublePreferencesKey("goal_protein_g")
        val GOAL_CARBS = doublePreferencesKey("goal_carbs_g")
        val GOAL_FAT = doublePreferencesKey("goal_fat_g")
        val SERVER_URL = stringPreferencesKey("server_url")
    }
}

/**
 * Returns the URL in the form Retrofit needs (ending in "/"), or null if it isn't a usable server URL.
 * HTTPS only, except plain HTTP to the emulator's host (10.0.2.2) for `wrangler dev`.
 */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" && url.host != "10.0.2.2") return null
    return url.toString().let { if (it.endsWith("/")) it else "$it/" }
}
