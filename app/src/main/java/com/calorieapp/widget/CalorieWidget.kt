package com.calorieapp.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.calorieapp.CalorieApplication
import com.calorieapp.MainActivity
import com.calorieapp.data.LoggedMeal
import com.calorieapp.data.NutritionTotals
import com.calorieapp.data.UserSettings
import com.calorieapp.data.totals
import com.calorieapp.ui.common.energyNumber
import java.time.LocalDate
import kotlin.math.abs

class CalorieWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalorieWidget()
}

/** Home-screen widget: today's energy vs goal, what's left, and macros. Tapping opens the app. */
class CalorieWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as CalorieApplication).container
        val today = LocalDate.now() // re-read on every update, so the widget moves on at midnight
        val mealsFlow = container.mealRepository.observeDay(today)
        val settingsFlow = container.settingsRepository.settings
        provideContent {
            val meals by mealsFlow.collectAsState(initial = emptyList<LoggedMeal>())
            val settings by settingsFlow.collectAsState(initial = UserSettings())
            GlanceTheme {
                WidgetContent(meals.flatMap { m -> m.foods.map { it.item } }.totals(), settings)
            }
        }
    }
}

@Composable
private fun WidgetContent(totals: NutritionTotals, settings: UserSettings) {
    val unit = settings.energyUnit
    val goals = settings.goals
    val left = goals.kcal - totals.calories
    val muted = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Today", style = muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                energyNumber(totals.calories, unit),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 28.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                " / ${energyNumber(goals.kcal, unit)} ${unit.label}",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                modifier = GlanceModifier.padding(bottom = 4.dp),
            )
        }
        LinearProgressIndicator(
            progress = fraction(totals.calories, goals.kcal),
            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            color = if (left < 0) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            if (left < 0) "${energyNumber(abs(left), unit)} ${unit.label} over" else "${energyNumber(left, unit)} ${unit.label} left",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
        )
        Text(
            "P ${Math.round(totals.proteinG)}/${Math.round(goals.proteinG)} · " +
                "C ${Math.round(totals.carbsG)}/${Math.round(goals.carbsG)} · " +
                "F ${Math.round(totals.fatG)}/${Math.round(goals.fatG)} g",
            style = muted,
        )
        Text(
            "Fibre ${Math.round(totals.fibreG)}/${Math.round(goals.fibreG)} g · " +
                "Fruit ${serves(totals.fruitServes)}/${serves(goals.fruitServes)} · " +
                "Veg ${serves(totals.vegServes)}/${serves(goals.vegServes)}",
            style = muted,
        )
    }
}

private fun serves(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

private fun fraction(value: Double, goal: Double): Float =
    if (goal <= 0) 0f else (value / goal).toFloat().coerceIn(0f, 1f)
