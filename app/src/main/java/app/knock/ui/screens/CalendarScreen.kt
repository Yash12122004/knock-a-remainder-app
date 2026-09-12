package app.knock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knock.data.TaskState
import app.knock.data.TaskStatus
import app.knock.data.friendly
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CalendarScreen(vm: MainViewModel, onOpenTask: (Long) -> Unit) {
    val c = LocalKnock.current
    val tasks by vm.tasks.collectAsState()
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    val byDay = remember(tasks) { tasks.groupBy { it.day } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f))
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous", tint = c.text) }
                IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next", tint = c.text) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                DayOfWeek.values().forEach { d ->
                    Text(d.name.take(2), color = c.secondary, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            val first = month.atDay(1)
            val lead = (first.dayOfWeek.value - 1) // Monday-first
            val cells = lead + month.lengthOfMonth()
            val rowsCount = (cells + 6) / 7
            for (r in 0 until rowsCount) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val idx = r * 7 + col - lead
                        if (idx < 0 || idx >= month.lengthOfMonth()) { Spacer(Modifier.weight(1f).height(48.dp)); continue }
                        val date = month.atDay(idx + 1)
                        val list = byDay[date].orEmpty().filter { it.state != TaskState.SKIPPED }
                        val dot: Color? = when {
                            list.isEmpty() -> null
                            list.all { it.state == TaskState.DONE } -> c.done
                            list.any { it.status(now) == TaskStatus.OVERDUE } -> c.overdue
                            else -> c.accent
                        }
                        val isSel = date == selected
                        Column(
                            Modifier.weight(1f).height(48.dp).padding(2.dp)
                                .background(if (isSel) c.accent else Color.Transparent, RoundedCornerShape(12.dp))
                                .border(1.dp, if (date == today) c.accent else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { selected = date },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                        ) {
                            Text("${date.dayOfMonth}", color = if (isSel) c.accentOn else c.text, fontSize = 14.sp,
                                fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                            Box(Modifier.size(6.dp).background(if (dot == null) Color.Transparent else if (isSel) c.accentOn else dot, CircleShape))
                        }
                    }
                }
            }
            SectionTitle(selected.friendly(today).uppercase() + " · " + selected.format(DateTimeFormatter.ofPattern("d MMM")))
        }
        val dayTasks = byDay[selected].orEmpty().sortedWith(compareBy({ it.anytime }, { it.dueAt }))
        if (dayTasks.isEmpty()) item { Text("Nothing scheduled.", color = c.secondary) }
        items(dayTasks, key = { it.id }) { t ->
            TimelineRow(t, now, onCircle = { if (t.state == TaskState.PENDING) vm.done(t.id) else if (t.state == TaskState.DONE) vm.undo(t.id) }, onOpen = { onOpenTask(t.id) })
        }
    }
}
