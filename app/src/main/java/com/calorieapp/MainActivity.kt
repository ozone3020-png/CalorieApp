package com.calorieapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.calorieapp.data.UserSettings
import com.calorieapp.ui.analyze.AnalyzeScreen
import com.calorieapp.ui.camera.CameraScreen
import com.calorieapp.ui.common.LocalEnergyUnit
import com.calorieapp.ui.log.LogScreen
import com.calorieapp.ui.log.LogViewModel
import com.calorieapp.ui.meal.AnalysisState
import com.calorieapp.ui.meal.MealViewModel
import com.calorieapp.ui.results.ResultsScreen
import com.calorieapp.ui.settings.SettingsScreen
import com.calorieapp.ui.settings.SettingsViewModel
import com.calorieapp.ui.theme.CalorieAppTheme
import kotlinx.serialization.Serializable

@Serializable object LogRoute
@Serializable object SettingsRoute
@Serializable object CameraRoute
@Serializable object AnalyzeRoute
@Serializable object ResultsRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsFlow = (application as CalorieApplication).container.settingsRepository.settings
        setContent {
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = UserSettings())
            CalorieAppTheme {
                CompositionLocalProvider(LocalEnergyUnit provides settings.energyUnit) {
                    CalorieNavHost()
                }
            }
        }
    }
}

@Composable
private fun CalorieNavHost() {
    val nav = rememberNavController()
    // Created outside the NavHost, so they are activity-scoped and shared by every screen.
    val meal: MealViewModel = viewModel(factory = MealViewModel.Factory)
    val log: LogViewModel = viewModel(factory = LogViewModel.Factory)
    val mealState by meal.state.collectAsStateWithLifecycle()

    fun backToCamera() {
        meal.reset()
        nav.popBackStack(CameraRoute, inclusive = false)
    }

    NavHost(nav, startDestination = LogRoute) {
        composable<LogRoute> {
            val state by log.state.collectAsStateWithLifecycle()
            val undo by log.undo.collectAsStateWithLifecycle()
            LogScreen(
                state = state,
                undo = undo,
                onPreviousDay = log::previousDay,
                onNextDay = log::nextDay,
                onPickDay = log::showDay,
                onAddMeal = { nav.navigate(CameraRoute) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onDeleteMeal = { log.deleteMeal(it) },
                onDeleteFood = { log.deleteFood(it) },
                onUndo = { log.undoDelete(it) },
                onUndoShown = log::undoShown,
            )
        }
        composable<SettingsRoute> {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
            SettingsScreen(vm, onBack = { nav.popBackStack() })
        }
        composable<CameraRoute> {
            CameraScreen(
                modifier = Modifier.fillMaxSize(),
                onPhotoConfirmed = { file, mode ->
                    meal.analyze(file, mode)
                    nav.navigate(AnalyzeRoute)
                },
                onBarcode = { code ->
                    meal.lookUpBarcode(code)
                    nav.navigate(AnalyzeRoute)
                },
            )
        }
        composable<AnalyzeRoute> {
            AnalyzeScreen(
                photo = meal.photo,
                state = mealState,
                mode = meal.mode,
                onSuccess = {
                    nav.navigate(ResultsRoute) { popUpTo<AnalyzeRoute> { inclusive = true } }
                },
                onRetry = meal::retry,
                onRetake = ::backToCamera,
            )
        }
        composable<ResultsRoute> {
            if (mealState !is AnalysisState.Success) {
                // Nothing to edit (e.g. restored after the app was killed): start over.
                LaunchedEffect(Unit) { backToCamera() }
            } else {
                val context = LocalContext.current
                ResultsScreen(
                    items = meal.editItems,
                    canSave = meal.canSave,
                    hasPendingLookups = meal.hasPendingLookups,
                    isLookingUp = meal.isLookingUp,
                    isSaving = meal.isSaving,
                    saveError = meal.saveError,
                    onNameChange = meal::updateName,
                    onNameCommit = meal::commitName,
                    onGramsChange = meal::updateGrams,
                    onUnitToggle = meal::toggleUnit,
                    onRemove = meal::removeItem,
                    onUpdateNutrition = meal::updateNutrition,
                    onSave = {
                        meal.save {
                            Toast.makeText(context, "Meal saved", Toast.LENGTH_SHORT).show()
                            log.showToday()
                            nav.popBackStack(LogRoute, inclusive = false)
                        }
                    },
                    onDiscard = ::backToCamera,
                )
            }
        }
    }
}
