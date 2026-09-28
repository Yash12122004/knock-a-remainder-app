package app.knock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knock.data.Priority
import app.knock.data.Task
import app.knock.data.friendly
import app.knock.ui.theme.LocalKnock
import java.time.LocalDate

/** Move a task to another day and, in the same step, change its priority. The time of day is kept — see [Task.movedTo]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSheet(task: Task, onDismiss: () -> Unit, onMove: (LocalDate, Priority) -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    val inAWeek = today.plusDays(7)
    var day by remember { mutableStateOf(if (task.day <= today) tomorrow else task.day) }
    var priority by remember { mutableStateOf(task.priority) }
    val hasTime = !task.anytime && task.dueAt != null
    val changed = day != task.day || priority != task.priority

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Move \"${task.title}\"", style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 2)
            Text(
                "Now: ${task.day.friendly(today)}" + if (hasTime) " at ${task.timeLabel()}. The time is kept." else ", any time.",
                color = c.secondary, fontSize = 13.sp
            )

            SectionTitle("TO")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task.day != today) Pill("Today", color = c.accent, filled = day == today) { day = today }
                Pill("Tomorrow", color = c.accent, filled = day == tomorrow) { day = tomorrow }
                Pill("In a week", color = c.accent, filled = day == inAWeek) { day = inAWeek }
            }
            Spacer(Modifier.height(6.dp))
            val custom = day != today && day != tomorrow && day != inAWeek
            Pill(if (custom) day.friendly(today) else "Pick a date…", color = c.accent, filled = custom) {
                pickDate(ctx, day) { picked -> day = maxOf(picked, today) }
            }

            SectionTitle("PRIORITY")
            PrioritySelector(priority) { priority = it }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { onMove(day, priority) }, enabled = changed,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn, disabledContainerColor = c.border, disabledContentColor = c.secondary)
            ) {
                Text(if (day != task.day) "Move to ${day.friendly(today)}" else "Save priority")
            }
        }
    }
}
