package com.calorieapp.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.calorieapp.CalorieApplication
import com.calorieapp.data.AnalyzeException
import com.calorieapp.data.AnalyzeRepository
import com.calorieapp.data.EnergyUnit
import com.calorieapp.data.Goals
import com.calorieapp.data.SettingsRepository
import com.calorieapp.data.UserSettings
import com.calorieapp.data.normalizeServerUrl
import com.calorieapp.ui.common.fmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Text fields for the goals, kept as typed so editing feels natural; energy is in the chosen unit. */
data class GoalFields(val energy: String, val protein: String, val carbs: String, val fat: String) {
    fun parse(unit: EnergyUnit): Goals? {
        val e = energy.toPositive() ?: return null
        return Goals(
            kcal = unit.toKcal(e),
            proteinG = protein.toPositive() ?: return null,
            carbsG = carbs.toPositive() ?: return null,
            fatG = fat.toPositive() ?: return null,
        )
    }

    companion object {
        fun from(goals: Goals, unit: EnergyUnit) = GoalFields(
            energy = Math.round(unit.fromKcal(goals.kcal)).toString(),
            protein = goals.proteinG.fmt(),
            carbs = goals.carbsG.fmt(),
            fat = goals.fatG.fmt(),
        )
    }
}

internal fun String.toPositive(): Double? =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 100_000 }

sealed interface ServerCheck {
    data object Idle : ServerCheck
    data object Checking : ServerCheck
    data object Ok : ServerCheck
    data class Failed(val message: String) : ServerCheck
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val analyze: AnalyzeRepository,
    val defaultServerUrl: String,
) : ViewModel() {

    var loaded by mutableStateOf<UserSettings?>(null)
        private set
    var energyUnit by mutableStateOf(EnergyUnit.KCAL)
        private set
    var goalFields by mutableStateOf(GoalFields("", "", "", ""))
        private set
    var serverUrl by mutableStateOf("")
        private set
    var serverCheck by mutableStateOf<ServerCheck>(ServerCheck.Idle)
        private set
    var saved by mutableStateOf(false)
        private set

    /**
     * The exact energy goal (kcal) behind the energy field while the user hasn't typed a new one.
     * The field shows a rounded number; converting that rounded number again would drift (2079 kcal -> 8699 kJ).
     */
    private var exactEnergyKcal: Double? = null

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            loaded = s
            energyUnit = s.energyUnit
            goalFields = GoalFields.from(s.goals, s.energyUnit)
            exactEnergyKcal = s.goals.kcal
            serverUrl = s.serverUrl.orEmpty()
        }
    }

    val goalsValid get() = goalFields.parse(energyUnit) != null

    /** The goals as they'd be saved, keeping the exact energy goal if the field wasn't retyped. */
    private fun currentGoals(): Goals? =
        goalFields.parse(energyUnit)?.let { g -> exactEnergyKcal?.let { g.copy(kcal = it) } ?: g }

    /** Blank means "use the built-in server". */
    val serverUrlValid get() = serverUrl.isBlank() || normalizeServerUrl(serverUrl) != null

    val canSave get() = loaded != null && goalsValid && serverUrlValid

    /** Switching unit converts the energy goal being edited, so the target itself doesn't change. */
    fun selectEnergyUnit(unit: EnergyUnit) {
        if (unit == energyUnit) return
        val kcal = exactEnergyKcal ?: goalFields.energy.toPositive()?.let(energyUnit::toKcal)
        energyUnit = unit
        if (kcal != null) goalFields = goalFields.copy(energy = Math.round(unit.fromKcal(kcal)).toString())
        saved = false
    }

    fun editGoals(fields: GoalFields) {
        if (fields.energy != goalFields.energy) exactEnergyKcal = null
        goalFields = fields
        saved = false
    }

    fun resetGoals() {
        editGoals(GoalFields.from(Goals.AU_REFERENCE, energyUnit))
        exactEnergyKcal = Goals.AU_REFERENCE.kcal
    }

    fun editServerUrl(url: String) {
        serverUrl = url
        serverCheck = ServerCheck.Idle
        saved = false
    }

    fun useBuiltInServer() = editServerUrl("")

    fun testServer() {
        val url = normalizeServerUrl(serverUrl.ifBlank { defaultServerUrl }) ?: return
        serverCheck = ServerCheck.Checking
        viewModelScope.launch {
            serverCheck = try {
                analyze.testServer(url)
                ServerCheck.Ok
            } catch (e: AnalyzeException) {
                ServerCheck.Failed(e.message.orEmpty())
            }
        }
    }

    fun save() {
        val goals = currentGoals() ?: return
        if (!serverUrlValid) return
        viewModelScope.launch {
            settings.setEnergyUnit(energyUnit)
            settings.setGoals(goals)
            settings.setServerUrl(serverUrl.ifBlank { null })
            saved = true
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as CalorieApplication).container
                SettingsViewModel(c.settingsRepository, c.analyzeRepository, c.defaultServerUrl)
            }
        }
    }
}
