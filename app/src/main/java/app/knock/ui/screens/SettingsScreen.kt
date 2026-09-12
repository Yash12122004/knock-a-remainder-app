package app.knock.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knock.data.Settings
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun SettingsScreen(vm: MainViewModel, onPermissions: () -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val perms = rememberPermState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClear by remember { mutableStateOf(false) }
    fun set(t: (Settings) -> Settings) = vm.updateSettings(t)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        vm.export { json ->
            try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }; scope.launch { snack.showSnackbar("Backup saved") } }
            catch (e: Exception) { scope.launch { snack.showSnackbar("Export failed: ${e.message}") } }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val json = try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() } catch (e: Exception) { null }
        if (json == null) { scope.launch { snack.showSnackbar("Couldn't read file") }; return@rememberLauncherForActivityResult }
        vm.import(json) { r -> scope.launch { snack.showSnackbar(r.fold({ "Imported $it tasks" }, { "Import failed: ${it.message}" })) } }
    }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Settings", style = MaterialTheme.typography.displayLarge, color = c.text, modifier = Modifier.padding(top = 8.dp))

            SectionTitle("PERMISSION HEALTH")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = perms.value
                Pill("Notifications", color = if (p.notif) c.done else c.overdue, filled = true) { onPermissions() }
                Pill("Exact alarms", color = if (p.exact) c.done else c.overdue, filled = true) { onPermissions() }
                Pill("Battery", color = if (p.battery) c.done else c.overdue, filled = true) { onPermissions() }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = perms.value
                Pill("Mic", color = if (p.mic) c.done else c.secondary, filled = true) { onPermissions() }
                Pill(if (p.loc) "Location" else "Location off", color = if (p.loc) c.done else c.warn, filled = true) { onPermissions() }
            }
            if (!perms.value.loc) Text("Location tasks fall back to their time reminder.", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))

            SectionTitle("REMIND EVERY (MINUTES)")
            KnockCardBox {
                IntRow("High priority", s.intervalHigh, 1, 120) { v -> set { it.copy(intervalHigh = v) } }
                IntRow("Normal priority", s.intervalNormal, 1, 240) { v -> set { it.copy(intervalNormal = v) } }
                IntRow("Low priority", s.intervalLow, 5, 720) { v -> set { it.copy(intervalLow = v) } }
                IntRow("Max snoozes per task per day", s.maxSnoozes, 0, 20) { v -> set { it.copy(maxSnoozes = v) } }
            }

            SectionTitle("BEHAVIOUR")
            KnockCardBox {
                KnockSwitchRow("Overdue 3+ days → daily digest", "Stops interval reminders and sends one summary a day", s.staleDigest) { v -> set { it.copy(staleDigest = v) } }
                KnockSwitchRow("Require a reason to skip", null, s.requireSkipReason) { v -> set { it.copy(requireSkipReason = v) } }
                KnockSwitchRow("Confirm before adding", "Show the parse screen before tasks are saved", s.confirmBeforeAdd) { v -> set { it.copy(confirmBeforeAdd = v) } }
                KnockSwitchRow("High priority can break DND", "Uses the alarm channel; allow DND bypass in system settings", s.highBreaksDnd) { v -> set { it.copy(highBreaksDnd = v) } }
            }

            SectionTitle("QUIET HOURS")
            KnockCardBox {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("From", color = c.secondary)
                    Pill(mins(s.quietStart), color = c.accent) { pickTime(ctx, LocalTime.of(s.quietStart / 60, s.quietStart % 60)) { t -> set { it.copy(quietStart = t.hour * 60 + t.minute) } } }
                    Text("to", color = c.secondary)
                    Pill(mins(s.quietEnd), color = c.accent) { pickTime(ctx, LocalTime.of(s.quietEnd / 60, s.quietEnd % 60)) { t -> set { it.copy(quietEnd = t.hour * 60 + t.minute) } } }
                }
                Text("Reminders are held and delivered as one catch-up digest when quiet hours end.", color = c.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }

            SectionTitle("PARSING & VOICE")
            KnockCardBox {
                Text("Bare number rule (\"by 2\")", color = c.text)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("PM_IF_PAST" to "PM if AM passed", "ALWAYS_PM" to "Always PM", "ALWAYS_AM" to "Always AM").forEach { (k, l) ->
                        Pill(l, color = c.accent, filled = s.bareNumberRule == k) { set { it.copy(bareNumberRule = k) } }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Voice language", color = c.text)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en-IN", "en-US", "en-GB", "hi-IN").forEach { l -> Pill(l, color = c.accent, filled = s.voiceLang == l) { set { it.copy(voiceLang = l) } } }
                }
            }

            SectionTitle("APPEARANCE")
            KnockCardBox { KnockSwitchRow("Dark theme", null, s.darkTheme) { v -> set { it.copy(darkTheme = v) } } }

            SectionTitle("DATA")
            KnockCardBox {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("knock-backup.json") }) { Text("Export JSON") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import JSON") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.loadDemo(); scope.launch { snack.showSnackbar("Demo day loaded") } }) { Text("Load demo day") }
                    TextButton(onClick = { showClear = true }) { Text("Clear all", color = c.overdue) }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName) })
                }, contentPadding = PaddingValues(0.dp)) { Text("Notification channels (DND bypass, sounds)", color = c.accent) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showClear) AlertDialog(
        onDismissRequest = { showClear = false }, title = { Text("Delete everything?") },
        text = { Text("All tasks, history and alarms. Export a backup first if you want one.") },
        confirmButton = { TextButton(onClick = { showClear = false; vm.clearAll() }) { Text("Delete all", color = c.overdue) } },
        dismissButton = { TextButton(onClick = { showClear = false }) { Text("Cancel") } }
    )
}

private fun mins(m: Int) = "%02d:%02d".format(m / 60, m % 60)

@Composable
private fun IntRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val c = LocalKnock.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { if (value > min) onChange(value - 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("−") }
        MonoText(value.toString(), size = 15, modifier = Modifier.width(44.dp), color = c.text)
        OutlinedButton(onClick = { if (value < max) onChange(value + 1) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("+") }
    }
}
