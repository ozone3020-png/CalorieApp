package com.calorieapp.ui.results

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import com.calorieapp.data.NutritionSource
import com.calorieapp.data.NutritionTotals
import com.calorieapp.data.totals
import com.calorieapp.ui.common.fmt
import com.calorieapp.ui.common.LocalEnergyUnit
import com.calorieapp.ui.common.energyNumber
import com.calorieapp.ui.common.energyText
import com.calorieapp.ui.meal.EditableFoodItem
import com.calorieapp.ui.meal.LookupState

/** Step 3: edit each item's name and weight, watch totals update, then save the meal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    items: List<EditableFoodItem>,
    canSave: Boolean,
    hasPendingLookups: Boolean,
    isLookingUp: Boolean,
    isSaving: Boolean,
    saveError: String?,
    onNameChange: (key: Long, name: String) -> Unit,
    onNameCommit: (key: Long) -> Unit,
    onGramsChange: (key: Long, grams: String) -> Unit,
    onUnitToggle: (key: Long) -> Unit,
    onRemove: (key: Long) -> Unit,
    onUpdateNutrition: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = !isSaving) { confirmDiscard = true }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this meal?") },
            text = { Text("The photo and results won't be saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your meal") },
                navigationIcon = {
                    IconButton(onClick = { confirmDiscard = true }, enabled = !isSaving) {
                        Icon(Icons.Filled.Close, contentDescription = "Discard meal")
                    }
                },
            )
        },
        bottomBar = {
            // imePadding keeps totals and Save above the keyboard while editing.
            Column(Modifier.imePadding().navigationBarsPadding().padding(16.dp)) {
                TotalsCard(items.mapNotNull { it.current }.totals())
                if (saveError != null) {
                    Text(
                        saveError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (hasPendingLookups) {
                    // Renamed items get fresh numbers first, so they can be reviewed before saving.
                    FilledTonalButton(
                        onClick = { focusManager.clearFocus(); onUpdateNutrition() },
                        enabled = !isLookingUp && !isSaving,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        if (isLookingUp) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Update nutrition")
                        }
                    }
                } else {
                    Button(
                        onClick = onSave,
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "All items removed. Close to retake the photo.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                // Tapping anywhere that isn't a field leaves the field (which commits a renamed item).
                modifier = Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    FoodItemEditor(
                        item = item,
                        enabled = !isSaving,
                        onNameChange = { onNameChange(item.key, it) },
                        onNameCommit = { onNameCommit(item.key) },
                        onGramsChange = { onGramsChange(item.key, it) },
                        onUnitToggle = { onUnitToggle(item.key) },
                        onRemove = { onRemove(item.key) },
                        onDone = { focusManager.clearFocus() },
                    )
                }
                item(key = "credits") {
                    Text(
                        "Nutrition data: Australian Food Composition Database – Release 3, © Food Standards " +
                            "Australia New Zealand (CC BY-SA 3.0 AU); USDA FoodData Central. " +
                            "\"AI estimate\" items are approximate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FoodItemEditor(
    item: EditableFoodItem,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onGramsChange: (String) -> Unit,
    onUnitToggle: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    var nameFocused by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = item.name,
                    onValueChange = onNameChange,
                    label = { Text("Food") },
                    singleLine = true,
                    enabled = enabled,
                    isError = !item.isNameValid || item.lookup is LookupState.Failed,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged {
                            // Leaving the field (Done, tapping elsewhere, another field) commits the new name.
                            if (nameFocused && !it.isFocused) onNameCommit()
                            nameFocused = it.isFocused
                        },
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = item.gramsText,
                    onValueChange = onGramsChange,
                    label = { Text("Amount") },
                    singleLine = true,
                    enabled = enabled,
                    isError = !item.isGramsValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.width(88.dp),
                )
                // Tap to switch between grams and millilitres.
                TextButton(
                    onClick = onUnitToggle,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.padding(top = 8.dp).width(44.dp),
                ) {
                    Text(item.unit.label, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.padding(top = 8.dp).size(40.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${item.name}")
                }
            }
            Spacer(Modifier.size(8.dp))
            SourceLine(item)
            Spacer(Modifier.size(2.dp))
            val current = item.current
            Text(
                if (current == null) {
                    "Enter an amount between 1 and ${EditableFoodItem.MAX_GRAMS.toInt()} ${item.unit.label}"
                } else {
                    "${energyText(current.calories, LocalEnergyUnit.current)} · P ${current.proteinG.fmt()} g · " +
                        "C ${current.carbsG.fmt()} g · F ${current.fatG.fmt()} g"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (current == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the numbers come from, or the progress/failure of a lookup for a renamed item. */
@Composable
private fun SourceLine(item: EditableFoodItem) {
    val style = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (val lookup = item.lookup) {
        LookupState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.width(6.dp))
            Text("Looking up \"${item.name.trim()}\"…", style = style, color = muted)
        }

        is LookupState.Failed -> Text(
            "${lookup.message} Edit the name, or change it back.",
            style = style,
            color = MaterialTheme.colorScheme.error,
        )

        LookupState.Idle -> when {
            item.needsLookup -> Text(
                "Name changed: tap Update nutrition to get its values",
                style = style,
                color = MaterialTheme.colorScheme.tertiary,
            )
            item.original.source == NutritionSource.AI -> Text(
                "AI estimate (not found in AFCD or USDA)",
                style = style,
                color = MaterialTheme.colorScheme.tertiary,
            )
            else -> Text(
                "${item.original.source.label} · ${item.original.match.orEmpty()}",
                style = style,
                color = muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TotalsCard(totals: NutritionTotals) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val unit = LocalEnergyUnit.current
            Stat("Energy (${unit.label})", energyNumber(totals.calories, unit))
            Stat("Protein", "${totals.proteinG.fmt()} g")
            Stat("Carbs", "${totals.carbsG.fmt()} g")
            Stat("Fat", "${totals.fatG.fmt()} g")
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
