package app.knock.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knock.data.Priority
import app.knock.data.TaskStatus
import app.knock.ui.theme.LocalKnock
import app.knock.ui.theme.Mono
import app.knock.ui.theme.knockCard
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun Pill(text: String, color: Color? = null, filled: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalKnock.current
    val col = color ?: c.secondary
    val shape = RoundedCornerShape(999.dp)
    var m = Modifier
        .background(if (filled) col else col.copy(alpha = 0.14f), shape)
        .border(1.dp, col.copy(alpha = if (filled) 1f else 0.35f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (filled) c.accentOn else col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MonoText(text: String, color: Color? = null, size: Int = 13, weight: FontWeight = FontWeight.SemiBold, modifier: Modifier = Modifier) {
    Text(text, color = color ?: LocalKnock.current.text, fontFamily = Mono, fontSize = size.sp, fontWeight = weight, modifier = modifier)
}

@Composable
fun WarningRow(text: String) {
    val c = LocalKnock.current
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = c.warn, modifier = Modifier.size(14.dp).padding(top = 1.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = c.warn, fontSize = 12.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LocalKnock.current.secondary,
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun StatusCircle(status: TaskStatus, onClick: () -> Unit) {
    val c = LocalKnock.current
    val col = when (status) {
        TaskStatus.DONE -> c.done; TaskStatus.OVERDUE -> c.overdue; TaskStatus.DUE -> c.accent
        TaskStatus.SKIPPED -> c.secondary; TaskStatus.UPCOMING -> c.secondary
    }
    Box(
        Modifier.size(26.dp).clip(CircleShape)
            .background(if (status == TaskStatus.DONE) col else Color.Transparent, CircleShape)
            .border(2.dp, col, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (status == TaskStatus.DONE) Icon(Icons.Filled.Check, contentDescription = "Done", tint = c.accentOn, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun PrioritySelector(value: Priority, onChange: (Priority) -> Unit) {
    val c = LocalKnock.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Priority.entries.forEach { p ->
            val col = when (p) { Priority.HIGH -> c.overdue; Priority.NORMAL -> c.accent; Priority.LOW -> c.secondary }
            Pill(p.name.lowercase().replaceFirstChar { it.uppercase() }, color = col, filled = value == p) { onChange(p) }
        }
    }
}

@Composable
fun KnockCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.knockCard().padding(14.dp), content = content)
}

@Composable
fun KnockSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalKnock.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = c.secondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = c.accentOn, checkedTrackColor = c.accent))
    }
}

@Composable
fun knockFieldColors(): TextFieldColors {
    val c = LocalKnock.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.border,
        cursorColor = c.accent, focusedLabelColor = c.accent, unfocusedLabelColor = c.secondary,
        focusedPlaceholderColor = c.secondary, unfocusedPlaceholderColor = c.secondary
    )
}

fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

fun pickTime(ctx: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, true).show()
}
