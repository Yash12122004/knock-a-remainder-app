package app.knock.ui.screens

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import app.knock.ui.theme.knockCard

@Composable
fun Glow(modifier: Modifier = Modifier, size: Int = 320) {
    val c = LocalKnock.current
    Box(
        modifier.size(size.dp).background(
            Brush.radialGradient(listOf(c.accent.copy(alpha = if (c.isDark) 0.28f else 0.16f), Color.Transparent)), CircleShape
        )
    )
}

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    val c = LocalKnock.current
    Box(Modifier.fillMaxSize()) {
        Glow(Modifier.align(Alignment.TopCenter).offset(y = (-80).dp))
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Knock", style = MaterialTheme.typography.displayLarge, color = c.text)
            Spacer(Modifier.height(24.dp))
            listOf(
                "Say or type everything you need to do, in one breath.",
                "Knock puts each task on today's timeline.",
                "Then it keeps knocking until you mark it done."
            ).forEachIndexed { i, line ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text("${i + 1}", color = c.accent, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(28.dp))
                    Text(line, color = c.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(36.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn)) {
                Text("Set up reminders", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---- permission state helpers

object Perms {
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun exactAlarms(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun battery(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun mic(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun location(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun openExactAlarmSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = Uri.parse("package:${ctx.packageName}") })
        }
    }

    fun openBatterySettings(ctx: Context) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${ctx.packageName}") })
        } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openAppSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${ctx.packageName}") })
    }
}

data class PermState(val notif: Boolean, val exact: Boolean, val battery: Boolean, val mic: Boolean, val loc: Boolean) {
    val requiredOk get() = notif && exact && battery
}

@Composable
fun rememberPermState(): State<PermState> {
    val ctx = LocalContext.current
    fun read() = PermState(Perms.notifications(ctx), Perms.exactAlarms(ctx), Perms.battery(ctx), Perms.mic(ctx), Perms.location(ctx))
    val state = remember { mutableStateOf(read()) }
    LifecycleResumeEffect(Unit) {
        state.value = read()
        onPauseOrDispose { }
    }
    return state
}

@Composable
fun PermissionsScreen(vm: MainViewModel, onContinue: () -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val perms = rememberPermState()
    var tick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val p = remember(tick, perms.value) {
        PermState(Perms.notifications(ctx), Perms.exactAlarms(ctx), Perms.battery(ctx), Perms.mic(ctx), Perms.location(ctx))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Permissions", style = MaterialTheme.typography.displayLarge, color = c.text)
        Text("Knock can only remind you if Android lets it.", color = c.secondary, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        PermRow("Notifications", "Every reminder is a notification. Without this, nothing shows.", p.notif, required = true) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else Perms.openAppSettings(ctx)
        }
        PermRow("Exact alarms", "Reminders fire at the exact minute, even in Doze.", p.exact, required = true) { Perms.openExactAlarmSettings(ctx) }
        PermRow("Unrestricted battery", "Stops the system from silently killing the reminder loop.", p.battery, required = true) { Perms.openBatterySettings(ctx) }
        PermRow("Microphone", "For speaking your tasks. You can always type instead.", p.mic, required = false) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        PermRow("Location", "Optional: \"remind me near the post office\". Falls back to time if off.", p.loc, required = false) {
            locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onContinue, enabled = p.requiredOk, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn, disabledContainerColor = c.card, disabledContentColor = c.secondary)
        ) { Text(if (p.requiredOk) "Continue" else "Grant the required ones to continue") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermRow(title: String, why: String, granted: Boolean, required: Boolean, onRequest: () -> Unit) {
    val c = LocalKnock.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).knockCard().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
                Spacer(Modifier.width(8.dp))
                Pill(if (required) "Required" else "Optional", color = if (required) c.accent else c.secondary)
            }
            Text(why, color = c.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(10.dp))
        if (granted) Pill("On", color = c.done, filled = true)
        else TextButton(onClick = onRequest) { Text("Allow", color = c.accent) }
    }
}
