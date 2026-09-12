package app.knock

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.knock.data.Settings
import app.knock.data.Task
import app.knock.data.TaskState
import app.knock.data.friendly
import app.knock.data.hhmm
import app.knock.ui.screens.MonoText
import app.knock.ui.screens.Pill
import app.knock.ui.screens.SkipSheet
import app.knock.ui.screens.pickTime
import app.knock.ui.theme.LocalKnock
import app.knock.ui.theme.KnockTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_OPEN_SKIP = "openSkip"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val app = application as KnockApp
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
        val openSkip = intent.getBooleanExtra(EXTRA_OPEN_SKIP, false)

        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
            val task by app.repo.observeTask(taskId).collectAsStateWithLifecycle(initialValue = null)
            var snoozesLeft by remember { mutableIntStateOf(settings.maxSnoozes) }
            LaunchedEffect(task?.snoozeCount, settings.maxSnoozes) { task?.let { snoozesLeft = app.repo.snoozesLeft(it) } }
            KnockTheme(dark = settings.darkTheme) {
                val t = task
                if (t == null || t.state != TaskState.PENDING) {
                    LaunchedEffect(t) { if (t != null) finish() }
                    Box(Modifier.fillMaxSize().background(LocalKnock.current.bg))
                } else {
                    ReminderContent(
                        task = t, snoozesLeft = snoozesLeft, requireReason = settings.requireSkipReason, openSkip = openSkip,
                        interval = t.intervalMin ?: settings.intervalFor(t.priority),
                        onDone = { lifecycleScope.launch { app.repo.markDone(t.id); finish() } },
                        onSnooze = { until -> lifecycleScope.launch { if (app.repo.snooze(t.id, until)) finish() } },
                        onSkip = { reason, all -> lifecycleScope.launch { app.repo.skip(t.id, reason, all); finish() } },
                        onOpen = {
                            startActivity(android.content.Intent(this@ReminderActivity, MainActivity::class.java).apply {
                                putExtra(MainActivity.EXTRA_ROUTE, "task/${t.id}"); flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            }); finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderContent(
    task: Task, snoozesLeft: Int, requireReason: Boolean, openSkip: Boolean, interval: Int,
    onDone: () -> Unit, onSnooze: (LocalDateTime) -> Unit, onSkip: (String, Boolean) -> Unit, onOpen: () -> Unit
) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    var showSkip by remember { mutableStateOf(openSkip) }
    val today = LocalDate.now()
    val due = task.effectiveDue()

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.align(Alignment.TopCenter).offset(y = 40.dp).size(360.dp)
            .background(Brush.radialGradient(listOf(c.accent.copy(alpha = if (c.isDark) 0.35f else 0.2f), Color.Transparent)), CircleShape))
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Text(if (task.day != today) task.day.friendly(today) else "Due", color = c.secondary, style = MaterialTheme.typography.labelMedium)
            MonoText(if (task.anytime) "anytime" else due.toLocalTime().hhmm(), size = 64, color = c.text)
            Spacer(Modifier.height(12.dp))
            Text(task.title, style = MaterialTheme.typography.headlineMedium, color = c.text, textAlign = TextAlign.Center, maxLines = 3)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.tag.isNotBlank()) Pill(task.tag)
                if (task.priority == app.knock.data.Priority.HIGH) Pill("High", color = c.overdue)
                Pill("Reminder ${task.remindCount} · next in $interval min", color = c.accent)
            }
            TextButton(onClick = onOpen) { Text("Open task", color = c.secondary) }

            Spacer(Modifier.weight(1f))

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.done, contentColor = c.accentOn)) {
                Text("Done", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(14.dp))
            Text(if (snoozesLeft > 0) "Snooze · $snoozesLeft left today" else "No snoozes left — reschedule or skip", color = c.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            val now = LocalDateTime.now()
            val tonight = today.atTime(21, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = snoozesLeft > 0
                SnoozeChip("10 min", enabled) { onSnooze(now.plusMinutes(10)) }
                SnoozeChip("1 h", enabled) { onSnooze(now.plusHours(1)) }
                SnoozeChip("Tonight 9 PM", enabled) { onSnooze(tonight) }
                SnoozeChip("Pick…", enabled) {
                    pickTime(ctx, now.toLocalTime().plusMinutes(30)) { t: LocalTime ->
                        val at = today.atTime(t).let { if (it.isAfter(now)) it else it.plusDays(1) }
                        onSnooze(at)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f).height(48.dp)) { Text("Reschedule") }
                OutlinedButton(onClick = { showSkip = true }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Skip…", color = c.secondary) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showSkip) SkipSheet(task = task, requireReason = requireReason, onDismiss = { showSkip = false }, onConfirm = onSkip)
}

@Composable
private fun SnoozeChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalKnock.current
    Pill(label, color = if (enabled) c.accent else c.secondary, onClick = if (enabled) onClick else null)
}
