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
import com.calorieapp.data.LoggedMeal
import com.calorieapp.data.MealRepository
import com.calorieapp.data.NutritionSource
import com.calorieapp.data.ScanMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** How the meal being edited started. */
enum class MealOrigin { PHOTO, MANUAL, EDIT }

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Loading : AnalysisState
    data class Success(val items: List<FoodItem>) : AnalysisState
    data class Error(val message: String, val canRetry: Boolean) : AnalysisState
}

/**
 * Holds one meal's journey: (photo / barcode → analysis, or typed foods, or a saved meal) → edit → save.
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

    var origin by mutableStateOf(MealOrigin.PHOTO)
        private set

    /** The day a new meal is logged on (the day being viewed in the log), or the edited meal's day. */
    var targetDay: LocalDate = LocalDate.now()
        private set
    private var editingMealId: Long? = null

    /** The items as they were when editing started, to tell whether anything changed. */
    private var initialItems: List<EditableFoodItem> = emptyList()

    /** Whether closing would lose anything: a photo, typed foods, or changes to a saved meal. */
    val hasUnsavedWork: Boolean
        get() = when (origin) {
            MealOrigin.PHOTO -> true
            MealOrigin.MANUAL -> editItems.isNotEmpty()
            MealOrigin.EDIT -> editItems.toList() != initialItems
        }

    val recentFoods: StateFlow<List<FoodItem>> = mealRepository.observeRecentFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favourites: StateFlow<List<FoodItem>> = mealRepository.observeFavourites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Starting a photo/barcode meal from the log: it will be saved on [day]. */
    fun prepare(day: LocalDate) {
        reset()
        origin = MealOrigin.PHOTO
        targetDay = day
        editingMealId = null
    }

    /** Typing foods in without a photo; saved on [day]. */
    fun startManual(day: LocalDate) {
        prepare(day)
        origin = MealOrigin.MANUAL
        _state.value = AnalysisState.Success(emptyList())
    }

    /** Editing a meal already in the log; Save replaces its foods. */
    fun startEdit(meal: LoggedMeal) {
        prepare(meal.loggedAt.atZone(ZoneId.systemDefault()).toLocalDate())
        origin = MealOrigin.EDIT
        editingMealId = meal.id
        editItems.addAll(meal.foods.map { EditableFoodItem(key = nextKey++, original = it.item) })
        initialItems = editItems.toList()
        _state.value = AnalysisState.Success(meal.foods.map { it.item })
    }

    /** Adds a food with known nutrition (from recent foods or favourites). */
    fun addFood(item: FoodItem) {
        editItems.add(EditableFoodItem(key = nextKey++, original = item))
    }

    /** Adds a food by name and looks up its nutrition straight away (100 g / 100 mL to start). */
    fun addNamedFood(name: String) {
        val blank = FoodItem(name = "", grams = 100.0, calories = 0.0, proteinG = 0.0, carbsG = 0.0, fatG = 0.0,
            source = NutritionSource.UNKNOWN)
        val key = nextKey++
        editItems.add(EditableFoodItem(key = key, original = blank, name = name.trim()))
        commitName(key)
    }

    fun isFavourite(item: FoodItem) = favourites.value.any { it.name.equals(item.name.trim(), ignoreCase = true) }

    fun toggleFavourite(item: FoodItem) {
        val makeFavourite = !isFavourite(item)
        viewModelScope.launch { mealRepository.setFavourite(item, makeFavourite) }
    }

    var mode by mutableStateOf(ScanMode.MEAL)
        private set

    private var barcode: String? = null

    fun analyze(file: File, mode: ScanMode) {
        photo = file
        barcode = null
        this.mode = mode
        retry()
    }

    fun lookUpBarcode(code: String) {
        photo?.delete()
        photo = null
        barcode = code
        mode = ScanMode.BARCODE
        retry()
    }

    fun retry() {
        val code = barcode
        val file = photo
        if (code == null && file == null) return
        job?.cancel()
        _state.value = AnalysisState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                val items = if (code != null) analyzeRepository.barcode(code) else analyzeRepository.analyze(file!!, mode)
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

    /**
     * Saves the items exactly as shown: a new meal on [targetDay], or the edited meal's new contents.
     * Then clears the flow and calls [onSaved].
     */
    fun save(onSaved: () -> Unit) {
        if (!canSave) return
        isSaving = true
        saveError = null
        val items = editItems.mapNotNull { it.current }
        val mealId = editingMealId
        viewModelScope.launch {
            try {
                if (origin == MealOrigin.EDIT && mealId != null) {
                    mealRepository.replaceMeal(mealId, items)
                } else {
                    mealRepository.saveMeal(items, at = timeOn(targetDay))
                }
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
        barcode = null
        editItems.clear()
        saveError = null
        _state.value = AnalysisState.Idle
    }

    /** Now if [day] is today; midday for a past day being filled in. */
    private fun timeOn(day: LocalDate): ZonedDateTime =
        if (day == LocalDate.now()) ZonedDateTime.now() else day.atTime(12, 0).atZone(ZoneId.systemDefault())

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
