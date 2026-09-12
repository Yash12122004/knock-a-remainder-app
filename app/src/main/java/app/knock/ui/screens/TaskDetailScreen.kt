package app.knock.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knock.data.EventType
import app.knock.data.Priority
import app.knock.data.Task
import app.knock.data.TaskState
import app.knock.data.friendly
import app.knock.data.hhmm
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val weekdayCodes = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

@Composable
fun TaskDetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val taskState by vm.repo.observeTask(id).collectAsState(initial = null)
    val events by vm.repo.observeEvents(id).collectAsState(initial = emptyList())
    val settings by vm.settings.collectAsState()
    val task = taskState
    if (task == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = c.text) }
            Text("Task not found", color = c.secondary)
        }
        return
    }

    var draft by remember(task.id) { mutableStateOf(task) }
    var showDelete by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }
    val dirty = draft != task
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = c.text) }
            Text("Task", style = MaterialTheme.typography.titleLarge, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                val text = "${task.title} — ${task.day.friendly(today)} ${task.timeLabel()}" + (task.notes.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share task"))
            }) { Icon(Icons.Filled.Share, contentDescription = "Share", tint = c.text) }
            IconButton(onClick = { showDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = c.overdue) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val st = task.status()
            Pill(st.name.lowercase().replaceFirstChar { it.uppercase() }, color = when (st) {
                app.knock.data.TaskStatus.DONE -> c.done; app.knock.data.TaskStatus.OVERDUE -> c.overdue
                app.knock.data.TaskStatus.DUE -> c.accent; else -> c.secondary
            }, filled = true)
            if (task.state == TaskState.PENDING) Text("reminded ${task.remindCount}× · snoozed ${if (task.snoozeDay == today) task.snoozeCount else 0}×", color = c.secondary, fontSize = 12.sp)
            if (task.skipReason != null) Text("skipped: ${task.skipReason}", color = c.secondary, fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = draft.title, onValueChange = { draft = draft.copy(title = it) }, label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(), colors = knockFieldColors(), shape = RoundedCornerShape(14.dp))

        SectionTitle("WHEN")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(draft.day.friendly(today), color = c.accent) { pickDate(ctx, draft.day) { d -> draft = draft.copy(day = d, dueAt = draft.dueAt?.toLocalTime()?.let { d.atTime(it) }) } }
            Pill(if (draft.anytime) "anytime" else draft.dueAt!!.toLocalTime().hhmm(), color = c.accent) {
                pickTime(ctx, draft.dueAt?.toLocalTime() ?: LocalTime.of(17, 0)) { t -> draft = draft.copy(dueAt = draft.day.atTime(t), anytime = false) }
            }
            if (!draft.anytime) Pill("clear time") { draft = draft.copy(dueAt = null, anytime = true) }
        }

        SectionTitle("OR WHEN NEAR")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft.locLabel ?: "", onValueChange = { draft = draft.copy(locLabel = it.ifBlank { null }) },
                placeholder = { Text("Place, e.g. Post office") }, modifier = Modifier.weight(1f), singleLine = true, colors = knockFieldColors(), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(value = draft.locRadius.toString(), onValueChange = { s -> s.toIntOrNull()?.let { draft = draft.copy(locRadius = it.coerceIn(50, 5000)) } },
                label = { Text("m") }, modifier = Modifier.width(90.dp), singleLine = true, colors = knockFieldColors(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        if (draft.hasLocation && !Perms.location(ctx)) WarningRow("Location is off — the time reminder will be used instead")
        if (draft.hasLocation && draft.locLat == null && task.locLabel == draft.locLabel) WarningRow("Couldn't find that place on the map yet — time fallback active")

        SectionTitle("REPEAT")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Never", color = c.accent, filled = draft.repeatRule == null) { draft = draft.copy(repeatRule = null) }
            Pill("Daily", color = c.accent, filled = draft.repeatRule == "DAILY") { draft = draft.copy(repeatRule = "DAILY") }
            Pill("Weekly", color = c.accent, filled = draft.repeatRule?.startsWith("WEEKLY") == true) { draft = draft.copy(repeatRule = "WEEKLY:" + draft.day.dayOfWeek.name.take(3)) }
        }
        if (draft.repeatRule?.startsWith("WEEKLY") == true) {
            val days = draft.repeatRule!!.removePrefix("WEEKLY:").split(",").filter { it.isNotBlank() }.toSet()
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                weekdayCodes.forEach { d ->
                    Pill(d.take(2), color = c.accent, filled = d in days) {
                        val nd = if (d in days) days - d else days + d
                        draft = draft.copy(repeatRule = "WEEKLY:" + weekdayCodes.filter { it in nd }.joinToString(","))
                    }
                }
            }
        }

        SectionTitle("PRIORITY")
        PrioritySelector(draft.priority) { draft = draft.copy(priority = it, intervalMin = null) }

        SectionTitle("TAG & REMIND EVERY")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = draft.tag, onValueChange = { draft = draft.copy(tag = it) }, label = { Text("Tag") },
                modifier = Modifier.weight(1f), singleLine = true, colors = knockFieldColors(), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(
                value = (draft.intervalMin ?: settings.intervalFor(draft.priority)).toString(),
                onValueChange = { s -> s.toIntOrNull()?.let { draft = draft.copy(intervalMin = it.coerceIn(1, 720)) } },
                label = { Text("min") }, modifier = Modifier.width(90.dp), singleLine = true, colors = knockFieldColors(), shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        SectionTitle("NOTES")
        OutlinedTextField(value = draft.notes, onValueChange = { draft = draft.copy(notes = it) }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            colors = knockFieldColors(), shape = RoundedCornerShape(14.dp))

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.update(draft) }, enabled = dirty && draft.title.isNotBlank(), modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn, disabledContainerColor = c.card, disabledContentColor = c.secondary)) { Text("Save") }
            if (task.state == TaskState.PENDING) OutlinedButton(onClick = { vm.done(task.id) }, modifier = Modifier.height(48.dp)) { Text("Done", color = c.done) }
            if (task.state == TaskState.PENDING) OutlinedButton(onClick = { showSkip = true }, modifier = Modifier.height(48.dp)) { Text("Skip…", color = c.secondary) }
            if (task.state != TaskState.PENDING) OutlinedButton(onClick = { vm.reschedule(task.id, maxOf(task.day, today), task.dueAt?.toLocalTime()) }, modifier = Modifier.height(48.dp)) { Text("Reopen") }
        }

        SectionTitle("HISTORY")
        if (events.isEmpty()) Text("No events yet.", color = c.secondary, fontSize = 13.sp)
        events.forEach { e ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                MonoText(e.at.format(DateTimeFormatter.ofPattern("d MMM HH:mm")), color = c.secondary, size = 11, modifier = Modifier.width(96.dp))
                Column {
                    Text(when (e.type) {
                        EventType.CREATED -> "Created"; EventType.REMINDED -> "Reminded"; EventType.SNOOZED -> "Snoozed"
                        EventType.SKIPPED -> "Skipped"; EventType.DONE -> "Done"; EventType.RESCHEDULED -> "Rescheduled"
                        EventType.HELD -> "Held (quiet hours)"; EventType.NEARBY -> "Nearby"
                    }, color = c.text, fontSize = 13.sp)
                    if (e.note.isNotBlank()) Text(e.note, color = c.secondary, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("Delete task?") }, text = { Text("Its alarms and notifications will be cancelled.") },
        confirmButton = { TextButton(onClick = { showDelete = false; vm.delete(task.id); onBack() }) { Text("Delete", color = c.overdue) } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
    )
    if (showSkip) SkipSheet(task = task, requireReason = settings.requireSkipReason, onDismiss = { showSkip = false }) { reason, all ->
        showSkip = false; vm.skip(task.id, reason, all)
    }
}

val skipReasons = listOf("Not needed anymore", "Already did it", "Will reschedule", "Blocked by someone")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkipSheet(task: Task, requireReason: Boolean, onDismiss: () -> Unit, onConfirm: (String, Boolean) -> Unit) {
    val c = LocalKnock.current
    var chip by remember { mutableStateOf<String?>(null) }
    var free by remember { mutableStateOf("") }
    var allFuture by remember { mutableStateOf(false) }
    val reason = listOfNotNull(chip, free.takeIf { it.isNotBlank() }).joinToString(" — ")
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if (c.isDark) androidx.compose.ui.graphics.Color(0xFF14162A) else c.card) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Skip \"${task.title}\"", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text("Why? The reason is saved to the task's history.", color = c.secondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                skipReasons.take(2).forEach { r -> Pill(r, color = c.accent, filled = chip == r) { chip = if (chip == r) null else r } }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                skipReasons.drop(2).forEach { r -> Pill(r, color = c.accent, filled = chip == r) { chip = if (chip == r) null else r } }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = free, onValueChange = { free = it }, placeholder = { Text("Anything else…") }, modifier = Modifier.fillMaxWidth(),
                colors = knockFieldColors(), shape = RoundedCornerShape(14.dp))
            if (task.repeatRule != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Just this one", color = c.accent, filled = !allFuture) { allFuture = false }
                    Pill("All future", color = c.overdue, filled = allFuture) { allFuture = true }
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = { onConfirm(reason.ifBlank { "No reason given" }, allFuture) }, enabled = !requireReason || reason.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn, disabledContainerColor = c.border, disabledContentColor = c.secondary)) {
                Text("Confirm skip")
            }
        }
    }
}
