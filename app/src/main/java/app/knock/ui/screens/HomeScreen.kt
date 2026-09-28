package app.knock.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knock.data.Task
import app.knock.data.TaskState
import app.knock.data.TaskStatus
import app.knock.data.friendly
import app.knock.data.hhmm
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import app.knock.ui.theme.knockCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private sealed class RailItem {
    data class TaskRow(val task: Task) : RailItem()
    data object NowLine : RailItem()
}

@Composable
fun HomeScreen(vm: MainViewModel, onCapture: () -> Unit, onConfirm: () -> Unit, onOpenTask: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val tasks by vm.tasks.collectAsState()
    val settings by vm.settings.collectAsState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }
    val today = now.toLocalDate()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val perms = rememberPermState()
    var moving by remember { mutableStateOf<Task?>(null) }

    val todayTasks = tasks.filter { it.day == today }
    val overdueEarlier = tasks.filter { it.day.isBefore(today) && it.state == TaskState.PENDING }
    val doneCount = todayTasks.count { it.state == TaskState.DONE }
    val total = todayTasks.count { it.state != TaskState.SKIPPED }
    // Everything lives on this one screen: overdue and today on the rail, then every upcoming day,
    // then finished work from earlier days, collapsed so it doesn't crowd what is still to do.
    val future = tasks.filter { it.day.isAfter(today) }
    val history = tasks.filter { it.day.isBefore(today) && it.state != TaskState.PENDING }.sortedByDescending { it.day }

    val rows = remember(todayTasks, overdueEarlier, now) {
        val list = mutableListOf<RailItem>()
        overdueEarlier.forEach { list += RailItem.TaskRow(it) }
        val sorted = todayTasks.sortedWith(compareBy({ it.anytime }, { it.dueAt }))
        var inserted = false
        for (t in sorted) {
            if (!inserted && (t.anytime || t.dueAt!!.isAfter(now))) { list += RailItem.NowLine; inserted = true }
            list += RailItem.TaskRow(t)
        }
        if (!inserted) list += RailItem.NowLine
        list
    }

    fun complete(task: Task) {
        vm.done(task.id)
        scope.launch {
            val r = snack.showSnackbar("Done: ${task.title}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.undo(task.id)
        }
    }

    fun move(task: Task, day: LocalDate, priority: app.knock.data.Priority) {
        vm.move(task.id, day, priority)
        scope.launch {
            val r = snack.showSnackbar("Moved to ${day.friendly(today)}: ${task.title}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.move(task.id, task.day, task.priority)
        }
    }

    fun longPressFor(task: Task): (() -> Unit)? = if (task.state == TaskState.PENDING) ({ moving = task }) else null

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = quick, onValueChange = { quick = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Add or say tasks…") }, colors = knockFieldColors(), shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        if (quick.isNotBlank()) TextButton(onClick = {
                            val text = quick; quick = ""
                            vm.quickAdd(text) { if (settings.confirmBeforeAdd) onConfirm() }
                        }) { Text("Add", color = c.accent) }
                    }
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(64.dp).background(c.accent.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                    FloatingActionButton(onClick = onCapture, containerColor = c.accent, contentColor = c.accentOn, shape = CircleShape, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = "Speak tasks")
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Glow(Modifier.align(Alignment.TopCenter).offset(y = (-120).dp), size = 360)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
                item {
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMM")), color = c.secondary, style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Today", style = MaterialTheme.typography.displayLarge, color = c.text)
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            MonoText("$doneCount/$total done", size = 14)
                            Text("\uD83D\uDD25 ${vm.streak}-day streak", color = c.secondary, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else doneCount.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = c.accent, trackColor = c.border
                    )
                    if (tasks.any { it.state == TaskState.PENDING }) {
                        Text("Long-press a task to move it or change its priority.", color = c.secondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                    }

                    if (settings.forceStopWarning) Banner(
                        "Reminders may have been blocked", "Knock was stopped and missed an alarm. Check battery settings.",
                        primary = "Battery settings" to { Perms.openBatterySettings(ctx); vm.dismissForceStopWarning() },
                        secondary = "Dismiss" to { vm.dismissForceStopWarning() }
                    )
                    if (settings.zoneChangePending) Banner(
                        "Time zone changed", "Keep task times as local clock times, or shift them to the same absolute moment?",
                        primary = "Keep local" to { vm.applyZoneChange(true) },
                        secondary = "Shift" to { vm.applyZoneChange(false) }
                    )
                    if (!perms.value.notif || !perms.value.exact) Banner(
                        "Permission problem", "Notifications or exact alarms are off — reminders won't fire.",
                        primary = "Fix" to onOpenSettings, secondary = null
                    )
                    if (overdueEarlier.isNotEmpty()) Banner(
                        "${overdueEarlier.size} overdue from earlier days", "Still pending. Move them all to today, or long-press one to move it.",
                        primary = "Move all to today" to { vm.moveAllToToday() }, secondary = null
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (rows.size == 1) item {
                    Text("Nothing on today's rail yet. Say or type your tasks below.", color = c.secondary, modifier = Modifier.padding(vertical = 40.dp))
                }
                items(rows, key = { r -> if (r is RailItem.TaskRow) "t${r.task.id}" else "now" }) { r ->
                    when (r) {
                        is RailItem.NowLine -> NowLineRow(now)
                        is RailItem.TaskRow -> TimelineRow(r.task, now, onCircle = {
                            if (r.task.state == TaskState.DONE) vm.undo(r.task.id) else if (r.task.state == TaskState.PENDING) complete(r.task)
                        }, onOpen = { onOpenTask(r.task.id) }, onLongPress = longPressFor(r.task))
                    }
                }

                future.groupBy { it.day }.toSortedMap().forEach { (day, list) ->
                    item(key = "hf$day") { SectionTitle(dayHeader(day, today)) }
                    items(list, key = { "f${it.id}" }) { t ->
                        TimelineRow(t, now, onCircle = { if (t.state == TaskState.PENDING) complete(t) else if (t.state == TaskState.DONE) vm.undo(t.id) },
                            onOpen = { onOpenTask(t.id) }, onLongPress = longPressFor(t), showDay = false)
                    }
                }

                if (history.isNotEmpty()) {
                    item(key = "h-history") {
                        Row(
                            Modifier.fillMaxWidth().clickable { showHistory = !showHistory }.padding(top = 18.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("EARLIER · DONE & SKIPPED (${history.size})", style = MaterialTheme.typography.labelMedium,
                                color = c.secondary, modifier = Modifier.weight(1f))
                            Text(if (showHistory) "Hide" else "Show", style = MaterialTheme.typography.labelMedium, color = c.accent)
                        }
                    }
                    if (showHistory) history.groupBy { it.day }.forEach { (day, list) ->
                        item(key = "hp$day") { SectionTitle(dayHeader(day, today)) }
                        items(list, key = { "p${it.id}" }) { t ->
                            TimelineRow(t, now, onCircle = { if (t.state == TaskState.DONE) vm.undo(t.id) }, onOpen = { onOpenTask(t.id) }, showDay = false)
                        }
                    }
                }
            }
        }
    }

    moving?.let { t -> MoveSheet(t, onDismiss = { moving = null }) { day, p -> moving = null; move(t, day, p) } }
}

/** "TOMORROW", "FRI 3 OCT" — with the year added only when it isn't this year. */
private fun dayHeader(day: LocalDate, today: LocalDate): String =
    (day.friendly(today) + if (day.year != today.year) " ${day.year}" else "").uppercase()

@Composable
fun Banner(title: String, body: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>?) {
    val c = LocalKnock.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).knockCard().padding(14.dp)) {
        Text(title, color = c.warn, style = MaterialTheme.typography.titleMedium)
        Text(body, color = c.secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = primary.second, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(primary.first, color = c.accent) }
            if (secondary != null) TextButton(onClick = secondary.second, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(secondary.first, color = c.secondary) }
        }
    }
}

@Composable
private fun NowLineRow(now: LocalDateTime) {
    val c = LocalKnock.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoText(now.toLocalTime().hhmm(), color = c.accent, size = 12, modifier = Modifier.width(56.dp))
        Box(Modifier.size(8.dp).background(c.accent, CircleShape))
        Box(Modifier.weight(1f).height(2.dp).background(c.accent))
        Spacer(Modifier.width(8.dp))
        Text("NOW", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimelineRow(
    task: Task, now: LocalDateTime, onCircle: () -> Unit, onOpen: () -> Unit,
    onLongPress: (() -> Unit)? = null, showDay: Boolean = true
) {
    val c = LocalKnock.current
    val status = task.status(now)
    // Finished work recedes: without hue, loudness is emphasis. The filled check circle still marks it done.
    // Overdue is marked by the red time and circle down the left edge; the title stays plain text,
    // otherwise several overdue rows turn into a wall of red.
    val titleColor = when (status) {
        TaskStatus.OVERDUE, TaskStatus.DUE -> c.text
        TaskStatus.DONE, TaskStatus.SKIPPED, TaskStatus.UPCOMING -> c.secondary
    }
    val timeColor = when (status) {
        TaskStatus.OVERDUE -> c.overdue; TaskStatus.DUE -> c.accent; else -> c.secondary
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(56.dp)) {
            MonoText(if (task.anytime) "any" else task.dueAt!!.toLocalTime().hhmm(), color = timeColor, size = 13)
            if (showDay && task.day != now.toLocalDate()) {
                // Red means overdue, so only a past day gets it.
                Text(task.day.friendly(now.toLocalDate()), color = if (task.day.isBefore(now.toLocalDate())) c.overdue else c.secondary, fontSize = 10.sp)
            }
        }
        StatusCircle(status, onCircle)
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.weight(1f).knockCard(14)
                .combinedClickable(onClick = onOpen, onLongClickLabel = if (onLongPress != null) "Move" else null, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                task.title, color = titleColor, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (status == TaskStatus.DONE || status == TaskStatus.SKIPPED) TextDecoration.LineThrough else null
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (task.tag.isNotBlank()) Pill(task.tag)
                if (task.priority == app.knock.data.Priority.HIGH) Pill("High", color = c.overdue)
                if (task.priority == app.knock.data.Priority.LOW) Pill("Low")
                if (task.hasLocation) Pill("near ${task.locLabel}")
                if (task.repeatRule != null) Pill("repeats")
                if (status == TaskStatus.OVERDUE && task.remindCount > 0) Text("${task.remindCount}× reminded", color = c.secondary, fontSize = 11.sp)
                if (status == TaskStatus.SKIPPED) Text("skipped · ${task.skipReason ?: ""}", color = c.secondary, fontSize = 11.sp)
            }
        }
    }
}
