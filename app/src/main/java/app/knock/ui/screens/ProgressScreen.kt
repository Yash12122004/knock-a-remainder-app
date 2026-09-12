package app.knock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knock.data.TaskState
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import java.time.LocalDate

@Composable
fun ProgressScreen(vm: MainViewModel) {
    val c = LocalKnock.current
    val tasks by vm.tasks.collectAsState()
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val perDay = days.map { d ->
        val list = tasks.filter { it.day == d && it.state != TaskState.SKIPPED }
        Triple(d, list.size, list.count { it.state == TaskState.DONE })
    }
    val weekTotal = perDay.sumOf { it.second }
    val weekDone = perDay.sumOf { it.third }
    val skipped = tasks.filter { it.state == TaskState.SKIPPED }
    val reasons = skipped.groupBy { (it.skipReason ?: "No reason").substringBefore(" — ") }.mapValues { it.value.size }.toList().sortedByDescending { it.second }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Progress", style = MaterialTheme.typography.displayLarge, color = c.text)

        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Streak", "${vm.streak}", "days · 1 grace/week", Modifier.weight(1f))
            StatCard("This week", if (weekTotal == 0) "—" else "${(100 * weekDone / weekTotal)}%", "$weekDone of $weekTotal done", Modifier.weight(1f))
        }

        SectionTitle("LAST 7 DAYS")
        KnockCardBox {
            Row(Modifier.fillMaxWidth().height(140.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                perDay.forEach { (d, total, done) ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        val frac = if (total == 0) 0f else done.toFloat() / total
                        Box(Modifier.fillMaxWidth().height((100 * (if (total == 0) 0.04f else 1f)).dp).background(c.border, RoundedCornerShape(6.dp)), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height((100 * frac).dp).background(if (frac >= 1f) c.done else c.accent, RoundedCornerShape(6.dp)))
                        }
                        Text(d.dayOfWeek.name.take(2), color = if (d == today) c.text else c.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(if (total == 0) "·" else "$done/$total", color = c.secondary, fontSize = 10.sp)
                    }
                }
            }
        }

        SectionTitle("WHY TASKS GET SKIPPED")
        KnockCardBox {
            if (reasons.isEmpty()) Text("Nothing skipped yet.", color = c.secondary)
            reasons.forEach { (r, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r, color = c.text, modifier = Modifier.weight(1f))
                    MonoText("$n", color = c.secondary)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    val c = LocalKnock.current
    KnockCardBox(modifier) {
        Text(label, color = c.secondary, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.displayLarge, color = c.text)
        Text(sub, color = c.secondary, fontSize = 12.sp)
    }
}
