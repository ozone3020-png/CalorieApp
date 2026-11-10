package com.calorieapp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.calorieapp.data.AiProvider
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

            Section("AI model") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AiProvider.entries.forEachIndexed { i, provider ->
                        SegmentedButton(
                            selected = vm.aiProvider == provider,
                            onClick = { vm.selectAiProvider(provider) },
                            shape = SegmentedButtonDefaults.itemShape(i, AiProvider.entries.size),
                        ) { Text(provider.label) }
                    }
                }
                Text(
                    when (vm.aiProvider) {
                        AiProvider.WORKERS_AI ->
                            "Meta's open model on Cloudflare Workers AI. Free daily allowance (resets 00:00 UTC)."
                        AiProvider.GEMINI ->
                            "Google's Gemini on its free API tier: usually more accurate at identifying food and " +
                                "reading labels. Google may use free-tier requests to improve its products."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Used for photos, labels and foods not in the databases. If it's unavailable or over its " +
                        "free limit, the other one is used automatically. Barcodes don't use AI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

            Section("Sync across devices") {
                SyncSection(vm)
            }

            Section("Your data") {
                DataSection(vm)
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
                        "• Foods found in neither are estimated by the AI model chosen above (Llama 4 Scout on " +
                        "Cloudflare Workers AI, or Google Gemini) and marked \"AI estimate\".",
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
        vm.aiProvider != s.aiProvider ||
        vm.serverUrl.trim() != s.serverUrl.orEmpty() ||
        goals == null ||
        GoalFields.from(goals, vm.energyUnit) != GoalFields.from(s.goals, vm.energyUnit)
}

@Composable
private fun SyncSection(vm: SettingsViewModel) {
    val prefs by vm.syncPrefs.collectAsStateWithLifecycle()
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var linkKey by rememberSaveable { mutableStateOf("") }
    var linkError by rememberSaveable { mutableStateOf(false) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn off sync on this phone?") },
            text = {
                Text(
                    "Your log stays on this phone, and the synced copy stays for your other phones. " +
                        "To turn sync back on with the same data, you'll need your sync key, so copy it first.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmOff = false; vm.turnOffSync() }) { Text("Turn off") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }

    val current = prefs
    if (current == null) {
        Text(
            "Keep your log on more than one phone. Your log is encrypted on the phone before it's uploaded, " +
                "so the server can't read it. Your sync key is your account: there's no password or email.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Button(onClick = vm::turnOnSync, enabled = !status.running) { Text("Turn on sync") }
        Text("Already syncing on another phone? Paste its sync key:", style = MaterialTheme.typography.bodySmall, color = muted)
        OutlinedTextField(
            value = linkKey,
            onValueChange = { linkKey = it; linkError = false },
            label = { Text("Sync key") },
            singleLine = true,
            isError = linkError,
            supportingText = if (linkError) ({ Text("That isn't a sync key. It's 43 letters, numbers, - and _.") }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { linkError = !vm.linkSync(linkKey) },
            enabled = linkKey.isNotBlank() && !status.running,
        ) { Text("Link this phone") }
    } else {
        Text(
            when {
                status.running -> "Syncing…"
                status.error != null -> status.error!!
                current.lastSyncedAt != null -> "Synced ${syncedAgo(current.lastSyncedAt)}."
                else -> "Sync is on."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.error != null && !status.running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text("Your sync key", style = MaterialTheme.typography.labelLarge)
        Text(
            if (showKey) current.key else "•".repeat(12) + current.key.takeLast(4),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(current.key)) }) { Text("Copy") }
        }
        Text(
            "Enter this key on another phone to sync it. Anyone with the key can see your log, so keep it private. " +
                "Save it somewhere safe (e.g. a password manager): it's the only way back to your synced log.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::syncNow, enabled = !status.running) { Text("Sync now") }
            OutlinedButton(onClick = { confirmOff = true }, enabled = !status.running) { Text("Turn off") }
        }
    }
}

private fun syncedAgo(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

@Composable
private fun DataSection(vm: SettingsViewModel) {
    val today = java.time.LocalDate.now()
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    // The system file picker: the user chooses where files go (Downloads, Google Drive, …). No permissions needed.
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::exportCsv)
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::backUp)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::restore)
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore a backup?") },
            text = {
                Text(
                    "Meals from the backup are added to your log (any already here are skipped). " +
                        "Your goals and settings are replaced with the ones in the backup.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                }) { Text("Choose backup file") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }

    Text(
        "Your log is stored only on this phone. Back it up to keep it safe if the phone is lost or reset, " +
            "or to move it to another phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { backupLauncher.launch("calorie-app-backup-$today.json") }, enabled = !vm.dataBusy) {
            Text("Back up")
        }
        OutlinedButton(onClick = { confirmRestore = true }, enabled = !vm.dataBusy) { Text("Restore") }
    }
    OutlinedButton(onClick = { csvLauncher.launch("calorie-log-$today.csv") }, enabled = !vm.dataBusy) {
        Text("Export spreadsheet (CSV)")
    }
    if (vm.dataBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    vm.dataMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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
