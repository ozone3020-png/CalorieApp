package com.calorieapp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calorieapp.BuildConfig
import com.calorieapp.data.EnergyUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val dirty = !vm.saved && vm.loaded != null && isChanged(vm)
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val leave = { if (dirty) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to settings haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding().padding(16.dp)) {
                Button(onClick = vm::save, enabled = vm.canSave && dirty, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.saved) "Saved" else "Save settings")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Energy unit") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    EnergyUnit.entries.forEachIndexed { i, unit ->
                        SegmentedButton(
                            selected = vm.energyUnit == unit,
                            onClick = { vm.selectEnergyUnit(unit) },
                            shape = SegmentedButtonDefaults.itemShape(i, EnergyUnit.entries.size),
                        ) {
                            Text(if (unit == EnergyUnit.KJ) "Kilojoules (kJ)" else "Calories (kcal)")
                        }
                    }
                }
            }

            Section("Daily goals") {
                val f = vm.goalFields
                NumberField("Energy", f.energy, vm.energyUnit.label) { vm.editGoals(f.copy(energy = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Protein", f.protein, "g", Modifier.weight(1f)) { vm.editGoals(f.copy(protein = it)) }
                    NumberField("Carbs", f.carbs, "g", Modifier.weight(1f)) { vm.editGoals(f.copy(carbs = it)) }
                    NumberField("Fat", f.fat, "g", Modifier.weight(1f)) { vm.editGoals(f.copy(fat = it)) }
                }
                Text(
                    "Defaults are the reference values Australian labels use for % daily intake " +
                        "(average adult: 8700 kJ, 50 g protein, 310 g carbs, 70 g fat). Set your own targets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = vm::resetGoals) { Text("Reset to Australian reference values") }
            }

            Section("Server") {
                OutlinedTextField(
                    value = vm.serverUrl,
                    onValueChange = vm::editServerUrl,
                    label = { Text("Server URL") },
                    placeholder = { Text("Built-in server") },
                    singleLine = true,
                    isError = !vm.serverUrlValid,
                    supportingText = {
                        Text(
                            if (!vm.serverUrlValid) "Enter an https:// address" else "Leave blank to use the built-in server",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "In use when blank: ${vm.defaultServerUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = vm::testServer, enabled = vm.serverUrlValid && vm.serverCheck != ServerCheck.Checking) {
                        Text("Test connection")
                    }
                    Spacer(Modifier.width(12.dp))
                    when (val check = vm.serverCheck) {
                        ServerCheck.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        ServerCheck.Ok -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("Working")
                        }
                        is ServerCheck.Failed -> Text(
                            check.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        ServerCheck.Idle -> Unit
                    }
                }
                if (vm.serverUrl.isNotBlank()) {
                    TextButton(onClick = vm::useBuiltInServer) { Text("Use built-in server") }
                }
            }

            Section("About") {
                Text("Calorie App ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Nutrition data:\n" +
                        "• Food Standards Australia New Zealand (2025). Australian Food Composition Database – " +
                        "Release 3. Licensed by FSANZ under a licence based on CC BY-SA 3.0 AU.\n" +
                        "• U.S. Department of Agriculture, FoodData Central (public domain).\n" +
                        "• Barcodes: Open Food Facts (openfoodfacts.org), Open Database License. Crowd-sourced: " +
                        "check numbers against the package.\n" +
                        "• Foods found in neither are estimated by an AI model (Llama 4 Scout via Cloudflare " +
                        "Workers AI) and marked \"AI estimate\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Portion sizes from photos are estimates. Check amounts before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun isChanged(vm: SettingsViewModel): Boolean {
    val s = vm.loaded ?: return false
    val goals = vm.goalFields.parse(vm.energyUnit)
    return vm.energyUnit != s.energyUnit ||
        vm.serverUrl.trim() != s.serverUrl.orEmpty() ||
        goals == null ||
        GoalFields.from(goals, vm.energyUnit) != GoalFields.from(s.goals, vm.energyUnit)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    suffix: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        isError = value.toPositive() == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
