package com.calorieapp

import android.os.Bundle
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.calorieapp.ui.analyze.AnalyzeScreen
import com.calorieapp.ui.camera.CameraScreen
import com.calorieapp.ui.meal.AnalysisState
import com.calorieapp.ui.meal.MealViewModel
import com.calorieapp.ui.results.ResultsScreen
import com.calorieapp.ui.theme.CalorieAppTheme
import kotlinx.serialization.Serializable

@Serializable object CameraRoute
@Serializable object AnalyzeRoute
@Serializable object ResultsRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CalorieAppTheme { CalorieNavHost() }
        }
    }
}

@Composable
private fun CalorieNavHost() {
    val nav = rememberNavController()
    // Created outside the NavHost, so it is activity-scoped and shared by every screen.
    val meal: MealViewModel = viewModel(factory = MealViewModel.Factory)
    val state by meal.state.collectAsStateWithLifecycle()

    fun backToCamera() {
        meal.reset()
        nav.popBackStack(CameraRoute, inclusive = false)
    }

    NavHost(nav, startDestination = CameraRoute) {
        composable<CameraRoute> {
            CameraScreen(
                modifier = Modifier.fillMaxSize(),
                onPhotoConfirmed = { file ->
                    meal.analyze(file)
                    nav.navigate(AnalyzeRoute)
                },
            )
        }
        composable<AnalyzeRoute> {
            AnalyzeScreen(
                photo = meal.photo,
                state = state,
                onSuccess = {
                    nav.navigate(ResultsRoute) { popUpTo<AnalyzeRoute> { inclusive = true } }
                },
                onRetry = meal::retry,
                onRetake = ::backToCamera,
            )
        }
        composable<ResultsRoute> {
            if (state !is AnalysisState.Success) {
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
                            // Step 4 will open the daily log here instead.
                            Toast.makeText(context, "Meal saved", Toast.LENGTH_SHORT).show()
                            nav.popBackStack(CameraRoute, inclusive = false)
                        }
                    },
                    onDiscard = ::backToCamera,
                )
            }
        }
    }
}
