package com.calorieapp.ui.meal

import android.database.sqlite.SQLiteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.calorieapp.data.FoodItem
import com.calorieapp.data.FoodUnit
import com.calorieapp.data.MealRepository
import com.calorieapp.data.ScanMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Loading : AnalysisState
    data class Success(val items: List<FoodItem>) : AnalysisState
    data class Error(val message: String, val canRetry: Boolean) : AnalysisState
}

/**
 * Holds one meal's journey: photo → analysis → edit → save.
 * Scoped to the activity so the camera, analysing and results screens share it.
 */
class MealViewModel(
    private val analyzeRepository: AnalyzeRepository,
    private val mealRepository: MealRepository,
) : ViewModel() {

    var photo by mutableStateOf<File?>(null)
        private set

    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    /** The results being edited. Snapshot state (not a Flow) so text fields update synchronously. */
    val editItems = mutableStateListOf<EditableFoodItem>()

    var isSaving by mutableStateOf(false)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    /** Some names were changed but their nutrition hasn't been fetched yet: offer "Update nutrition", not Save. */
    val hasPendingLookups get() = editItems.any { it.needsLookup }

    val isLookingUp get() = editItems.any { it.lookup == LookupState.Loading }

    /** Saving is only offered once every item shows the numbers that will be saved. */
    val canSave
        get() = !isSaving && editItems.isNotEmpty() && !hasPendingLookups &&
            editItems.all { it.isValid && it.lookup == LookupState.Idle }

    /** Fetches nutrition for every renamed item, so the user can review it before saving. */
    fun updateNutrition() {
        editItems.filter { it.needsLookup }.forEach { commitName(it.key) }
    }

    private var job: Job? = null
    private val lookupJobs = mutableMapOf<Long, Job>()
    private var nextKey = 0L

    var mode by mutableStateOf(ScanMode.MEAL)
        private set

    fun analyze(file: File, mode: ScanMode) {
        photo = file
        this.mode = mode
        retry()
    }

    fun retry() {
        val file = photo ?: return
        job?.cancel()
        _state.value = AnalysisState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                val items = analyzeRepository.analyze(file, mode)
                if (items.isEmpty()) {
                    AnalysisState.Error("No food found in this photo. Try a closer, well-lit shot.", canRetry = false)
                } else {
                    editItems.clear()
                    editItems.addAll(items.map { EditableFoodItem(key = nextKey++, original = it) })
                    AnalysisState.Success(items)
                }
            } catch (e: AnalyzeException) {
                AnalysisState.Error(e.message.orEmpty(), canRetry = true)
            }
        }
    }

    /** Typing a new name cancels any lookup in flight; the lookup runs when editing finishes. */
    fun updateName(key: Long, name: String) {
        lookupJobs.remove(key)?.cancel()
        edit(key) { it.copy(name = name, lookup = LookupState.Idle) }
    }

    /** Called when the user leaves the name field: fetch nutrition for the new name. */
    fun commitName(key: Long) {
        val item = editItems.firstOrNull { it.key == key } ?: return
        if (!item.needsLookup || item.lookup == LookupState.Loading) return
        lookupJobs[key] = viewModelScope.launch { lookUp(key) }
    }

    fun updateGrams(key: Long, gramsText: String) = edit(key) { it.copy(gramsText = gramsText) }

    /** Switches between g and mL. Most foods and drinks are close to 1 g per mL, so values aren't rescaled. */
    fun toggleUnit(key: Long) = edit(key) {
        it.copy(unit = if (it.unit == FoodUnit.GRAMS) FoodUnit.MILLILITRES else FoodUnit.GRAMS)
    }

    fun removeItem(key: Long) {
        lookupJobs.remove(key)?.cancel()
        editItems.removeAll { it.key == key }
    }

    /** Replaces an item's nutrition with that of its (new) name. Returns false on failure. */
    private suspend fun lookUp(key: Long): Boolean {
        val item = editItems.firstOrNull { it.key == key } ?: return false
        val name = item.name.trim()
        edit(key) { it.copy(lookup = LookupState.Loading) }
        return try {
            val found = analyzeRepository.lookup(name, item.lookupGrams, item.unit)
            // Only apply if the user hasn't typed something else meanwhile.
            edit(key) { if (it.name.trim() == name) it.copy(original = found.copy(name = name), lookup = LookupState.Idle) else it }
            true
        } catch (e: AnalyzeException) {
            edit(key) { it.copy(lookup = LookupState.Failed(e.message.orEmpty())) }
            false
        } finally {
            lookupJobs.remove(key)
        }
    }

    private inline fun edit(key: Long, change: (EditableFoodItem) -> EditableFoodItem) {
        val i = editItems.indexOfFirst { it.key == key }
        if (i >= 0) editItems[i] = change(editItems[i])
    }

    /** Saves the edited items, exactly as shown, as one meal; then clears the flow and calls [onSaved]. */
    fun save(onSaved: () -> Unit) {
        if (!canSave) return
        isSaving = true
        saveError = null
        viewModelScope.launch {
            try {
                mealRepository.saveMeal(editItems.mapNotNull { it.current })
                reset()
                onSaved()
            } catch (e: SQLiteException) {
                saveError = "Couldn't save the meal: ${e.message}"
            } finally {
                isSaving = false
            }
        }
    }

    /** Abandons the current meal and deletes its photo. */
    fun reset() {
        job?.cancel()
        lookupJobs.values.forEach { it.cancel() }
        lookupJobs.clear()
        photo?.delete()
        photo = null
        editItems.clear()
        saveError = null
        _state.value = AnalysisState.Idle
    }

    override fun onCleared() {
        photo?.delete()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as CalorieApplication).container
                MealViewModel(container.analyzeRepository, container.mealRepository)
            }
        }
    }
}
