package app.knock.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.knock.data.Priority
import app.knock.data.Task
import app.knock.data.TaskState
import app.knock.data.friendly
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

enum class AllFilter(val label: String, val empty: String) {
    PENDING("Pending", "Nothing pending. Add tasks from the Today tab."),
    DONE("Done", "Nothing done yet."),
    SKIPPED("Skipped", "Nothing skipped."),
    EVERYTHING("All", "No tasks yet.")
}

/** Every task in one list, whatever its day. Today only looks a week ahead; Calendar shows one day at a time. */
@Composable
fun AllTasksScreen(vm: MainViewModel, onOpenTask: (Long) -> Unit) {
    val c = LocalKnock.current
    val tasks by vm.tasks.collectAsState()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }
    val today = now.toLocalDate()
    var filter by rememberSaveable { mutableStateOf(AllFilter.PENDING) }
    var moving by remember { mutableStateOf<Task?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun matches(t: Task, f: AllFilter) = when (f) {
        AllFilter.PENDING -> t.state == TaskState.PENDING
        AllFilter.DONE -> t.state == TaskState.DONE
        AllFilter.SKIPPED -> t.state == TaskState.SKIPPED
        AllFilter.EVERYTHING -> true
    }
    val shown = tasks.filter { matches(it, filter) }
    // Pending tasks from earlier days get their own section at the top, so nothing slips below today unseen.
    val overdue = if (filter == AllFilter.PENDING) shown.filter { it.day.isBefore(today) } else emptyList()
    // Pending and Everything read forwards like a schedule; Done and Skipped read backwards like a log.
    val newestFirst = filter == AllFilter.DONE || filter == AllFilter.SKIPPED
    val byDay = (shown - overdue.toSet()).groupBy { it.day }
        .toSortedMap(if (newestFirst) reverseOrder<LocalDate>() else naturalOrder<LocalDate>())

    fun complete(task: Task) {
        vm.done(task.id)
        scope.launch {
            val r = snack.showSnackbar("Done: ${task.title}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.undo(task.id)
        }
    }

    fun move(task: Task, day: LocalDate, priority: Priority) {
        vm.move(task.id, day, priority)
        scope.launch {
            val r = snack.showSnackbar("Moved to ${day.friendly(today)}: ${task.title}", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.move(task.id, task.day, task.priority)
        }
    }

    @Composable
    fun TaskRow(t: Task, showDay: Boolean) = TimelineRow(
        t, now,
        onCircle = { if (t.state == TaskState.PENDING) complete(t) else if (t.state == TaskState.DONE) vm.undo(t.id) },
        onOpen = { onOpenTask(t.id) },
        onLongPress = if (t.state == TaskState.PENDING) ({ moving = t }) else null,
        showDay = showDay
    )

    Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp)) {
            item {
                Text("${shown.size} ${if (shown.size == 1) "task" else "tasks"}", color = c.secondary, style = MaterialTheme.typography.labelMedium)
                Text("All tasks", style = MaterialTheme.typography.displayLarge, color = c.text)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AllFilter.entries.forEach { f ->
                        Pill("${f.label} ${tasks.count { matches(it, f) }}", color = c.accent, filled = filter == f) { filter = f }
                    }
                }
                if (filter == AllFilter.PENDING && shown.isNotEmpty()) {
                    Text("Long-press a task to move it or change its priority.", color = c.secondary,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (shown.isEmpty()) item {
                Text(filter.empty, color = c.secondary, modifier = Modifier.padding(vertical = 40.dp))
            }

            if (overdue.isNotEmpty()) {
                item(key = "h-overdue") { SectionTitle("OVERDUE") }
                items(overdue, key = { "o${it.id}" }) { t -> TaskRow(t, showDay = true) }
            }

            byDay.forEach { (day, list) ->
                item(key = "h$day") { SectionTitle(dayHeader(day, today)) }
                items(list, key = { "d${it.id}" }) { t -> TaskRow(t, showDay = false) }
            }
        }
    }

    moving?.let { t -> MoveSheet(t, onDismiss = { moving = null }) { day, p -> moving = null; move(t, day, p) } }
}

private fun dayHeader(day: LocalDate, today: LocalDate): String =
    (day.friendly(today) + if (day.year != today.year) " ${day.year}" else "").uppercase()
