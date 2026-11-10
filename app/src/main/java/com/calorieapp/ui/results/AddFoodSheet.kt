package com.calorieapp.ui.results

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calorieapp.data.FoodItem
import com.calorieapp.data.NutritionSource
import com.calorieapp.ui.common.LocalEnergyUnit
import com.calorieapp.ui.common.energyText
import com.calorieapp.ui.common.fmt

/**
 * Pick a food to add: type a name to look it up, or tap a favourite / recent food to add it with the
 * amount and nutrition it was last logged with. The star toggles a favourite.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(
    recent: List<FoodItem>,
    favourites: List<FoodItem>,
    onPick: (FoodItem) -> Unit,
    onLookUp: (String) -> Unit,
    onToggleFavourite: (FoodItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val favouriteNames = favourites.map { it.name.lowercase() }.toSet()
    val matchingFavourites = favourites.filter { it.matches(q) }
    val matchingRecent = recent.filter { it.matches(q) && it.name.lowercase() !in favouriteNames }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 16.dp).imePadding()) {
            Text("Add food", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search, or type any food") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { if (q.isNotEmpty()) onLookUp(q) }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                if (q.isNotEmpty()) {
                    item(key = "lookup") {
                        ListItem(
                            headlineContent = { Text("Look up \"$q\"") },
                            supportingContent = { Text("Nutrition from AFCD, then USDA, then an AI estimate") },
                            leadingContent = { Icon(Icons.Filled.Search, contentDescription = null) },
                            modifier = Modifier.clickable { onLookUp(q) },
                        )
                    }
                }
                if (matchingFavourites.isNotEmpty()) {
                    item(key = "fav-header") { SectionHeader("Favourites") }
                    items(matchingFavourites, key = { "fav-" + it.name }) { food ->
                        QuickFoodRow(food, isFavourite = true, onPick = { onPick(food) }, onStar = { onToggleFavourite(food) })
                    }
                }
                if (matchingRecent.isNotEmpty()) {
                    item(key = "recent-header") { SectionHeader("Recent") }
                    items(matchingRecent, key = { "recent-" + it.name }) { food ->
                        QuickFoodRow(food, isFavourite = false, onPick = { onPick(food) }, onStar = { onToggleFavourite(food) })
                    }
                }
                if (q.isEmpty() && favourites.isEmpty() && recent.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Foods you log will show up here for one-tap re-logging. Type a food above to look it up.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun QuickFoodRow(food: FoodItem, isFavourite: Boolean, onPick: () -> Unit, onStar: () -> Unit) {
    val source = when (food.source) {
        NutritionSource.UNKNOWN -> ""
        NutritionSource.AI -> " · AI est."
        else -> " · ${food.source.label}"
    }
    ListItem(
        headlineContent = { Text(food.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                "${food.grams.fmt()} ${food.unit.label} · ${energyText(food.calories, LocalEnergyUnit.current)}$source",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onStar) {
                Icon(
                    if (isFavourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                    tint = if (isFavourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onPick),
    )
}

private fun FoodItem.matches(query: String) = query.isEmpty() || name.contains(query, ignoreCase = true)
