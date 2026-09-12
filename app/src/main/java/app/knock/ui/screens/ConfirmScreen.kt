package app.knock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.knock.data.friendly
import app.knock.data.hhmm
import app.knock.parse.ParsedTask
import app.knock.ui.MainViewModel
import app.knock.ui.theme.LocalKnock
import app.knock.ui.theme.knockCard
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun ConfirmScreen(vm: MainViewModel, onReRecord: () -> Unit, onAdded: () -> Unit, onBack: () -> Unit) {
    val c = LocalKnock.current
    val ctx = LocalContext.current
    val parsed = vm.parsed
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = c.text) }
            Column {
                Text("I heard ${parsed.size} task${if (parsed.size == 1) "" else "s"}", style = MaterialTheme.typography.titleLarge, color = c.text)
                Text("Tap anything to fix it", color = c.secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (vm.transcript.isNotBlank()) {
            Text("\u201C${vm.transcript}\u201D", color = c.secondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            itemsIndexed(parsed, key = { i, p -> "$i-${p.original}" }) { i, p ->
                val dup = vm.duplicateOf(p)
                Column(Modifier.fillMaxWidth().knockCard().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = p.title, onValueChange = { vm.updateParsed(i, p.copy(title = it)) },
                            modifier = Modifier.weight(1f), singleLine = true, colors = knockFieldColors(), shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = { vm.removeParsed(i) }) { Icon(Icons.Filled.Close, contentDescription = "Remove", tint = c.secondary) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(p.day.friendly(today), color = c.accent) { pickDate(ctx, p.day) { d -> vm.updateParsed(i, p.copy(day = d, rolled = false, warnings = p.warnings.filterNot { it.contains("moved to tomorrow") })) } }
                        Pill(if (p.anytime) "anytime" else p.time!!.hhmm(), color = c.accent) {
                            pickTime(ctx, p.time ?: LocalTime.of(17, 0)) { t -> vm.updateParsed(i, p.copy(time = t, anytime = false, warnings = p.warnings.filterNot { it.startsWith("No time") || it.startsWith("Assumed") })) }
                        }
                        if (!p.anytime) Pill("clear time", color = c.secondary) { vm.updateParsed(i, p.copy(time = null, anytime = true)) }
                    }
                    if (p.locLabel != null) {
                        Spacer(Modifier.height(6.dp))
                        Pill("near ${p.locLabel}", color = c.done)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrioritySelector(p.priority) { vm.updateParsed(i, p.copy(priority = it)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = p.tag, onValueChange = { vm.updateParsed(i, p.copy(tag = it)) }, label = { Text("Tag") },
                        singleLine = true, colors = knockFieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.width(180.dp)
                    )
                    if (p.rolled) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { vm.updateParsed(i, p.copy(day = today, rolled = false, warnings = p.warnings.filterNot { it.contains("moved to tomorrow") })) }, contentPadding = PaddingValues(0.dp)) {
                            Text("Keep it today", color = c.accent)
                        }
                    }
                    p.warnings.forEach { WarningRow(it) }
                    if (dup != null) WarningRow("Looks like a duplicate of \"${dup.title}\" (${dup.day.friendly(today)})")
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onReRecord, modifier = Modifier.height(50.dp)) { Text("Re-record") }
            Button(
                onClick = { vm.addParsed(parsed) { onAdded() } }, enabled = parsed.isNotEmpty(),
                modifier = Modifier.weight(1f).height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentOn)
            ) { Text("Add ${parsed.size} task${if (parsed.size == 1) "" else "s"}") }
        }
    }
}
