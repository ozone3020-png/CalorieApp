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
import com.calorieapp.data.AiProvider
import android.net.Uri
import com.calorieapp.data.AnalyzeRepository
import com.calorieapp.data.BackupException
import com.calorieapp.data.BackupRepository
import com.calorieapp.data.EnergyUnit
import com.calorieapp.data.Goals
import com.calorieapp.data.SettingsRepository
import com.calorieapp.data.UserSettings
import com.calorieapp.data.normalizeServerUrl
import com.calorieapp.ui.common.fmt
import com.calorieapp.data.SyncPrefs
import com.calorieapp.data.sync.SyncCrypto
import com.calorieapp.data.sync.SyncRepository
import com.calorieapp.data.sync.SyncStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val backup: BackupRepository,
    private val syncRepo: SyncRepository,
    val defaultServerUrl: String,
) : ViewModel() {

    /** Sync on this phone (null = off), live. */
    val syncPrefs: StateFlow<SyncPrefs?> = settings.settings.map { it.sync }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val syncStatus: StateFlow<SyncStatus> = syncRepo.status

    fun turnOnSync() = viewModelScope.launch { syncRepo.enable() }

    /** Links this phone to the account of a key copied from another phone. Returns false if it isn't a key. */
    fun linkSync(key: String): Boolean {
        val trimmed = key.trim()
        if (!SyncCrypto.isValidKey(trimmed)) return false
        viewModelScope.launch { syncRepo.enable(trimmed) }
        return true
    }

    fun syncNow() = viewModelScope.launch { syncRepo.syncNow() }

    fun turnOffSync() = viewModelScope.launch { syncRepo.disable() }

    /** Result of the last export / backup / restore, shown under the buttons. */
    var dataMessage by mutableStateOf<String?>(null)
        private set
    var dataBusy by mutableStateOf(false)
        private set

    fun exportCsv(uri: Uri) = dataTask { "Exported ${backup.writeCsv(uri)} foods to a spreadsheet file." }

    fun backUp(uri: Uri) = dataTask { "Backed up ${backup.writeBackup(uri)} meals, your favourites and settings." }

    fun restore(uri: Uri) = dataTask {
        val r = backup.restore(uri)
        // Restored settings change what's on screen here too.
        settings.settings.first().let { s ->
            loaded = s
            energyUnit = s.energyUnit
            aiProvider = s.aiProvider
            goalFields = GoalFields.from(s.goals, s.energyUnit)
            exactEnergyKcal = s.goals.kcal
        }
        buildString {
            append("Restored ${r.mealsAdded} meal${if (r.mealsAdded == 1) "" else "s"}")
            if (r.mealsSkipped > 0) append(" (${r.mealsSkipped} already in your log)")
            append(", ${r.favourites} favourite${if (r.favourites == 1) "" else "s"}")
            if (r.settingsRestored) append(" and your settings")
            append(".")
        }
    }

    private fun dataTask(block: suspend () -> String) {
        dataBusy = true
        dataMessage = null
        viewModelScope.launch {
            dataMessage = try {
                block()
            } catch (e: BackupException) {
                e.message
            } finally {
                dataBusy = false
            }
        }
    }

    var loaded by mutableStateOf<UserSettings?>(null)
        private set
    var energyUnit by mutableStateOf(EnergyUnit.KCAL)
        private set
    var aiProvider by mutableStateOf(AiProvider.WORKERS_AI)
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
            aiProvider = s.aiProvider
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

    fun selectAiProvider(provider: AiProvider) {
        aiProvider = provider
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
            settings.setAiProvider(aiProvider)
            settings.setGoals(goals)
            settings.setServerUrl(serverUrl.ifBlank { null })
            saved = true
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as CalorieApplication).container
                SettingsViewModel(
                    c.settingsRepository, c.analyzeRepository, c.backupRepository, c.syncRepository, c.defaultServerUrl,
                )
            }
        }
    }
}
