package app.knock.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class Priority { LOW, NORMAL, HIGH }
enum class TaskState { PENDING, DONE, SKIPPED }
enum class EventType { CREATED, REMINDED, SNOOZED, SKIPPED, DONE, RESCHEDULED, HELD, NEARBY }
enum class TaskStatus { UPCOMING, DUE, OVERDUE, DONE, SKIPPED }

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val day: LocalDate,
    val dueAt: LocalDateTime?,
    val anytime: Boolean,
    val priority: Priority = Priority.NORMAL,
    val tag: String = "",
    val notes: String = "",
    /** null, "DAILY", or "WEEKLY:MON,WED,FRI" */
    val repeatRule: String? = null,
    val locLabel: String? = null,
    val locLat: Double? = null,
    val locLng: Double? = null,
    val locRadius: Int = 200,
    val intervalMin: Int? = null,
    val state: TaskState = TaskState.PENDING,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val doneAt: LocalDateTime? = null,
    val skipReason: String? = null,
    val parseNote: String? = null,
    val remindCount: Int = 0,
    val snoozeCount: Int = 0,
    val snoozeDay: LocalDate? = null,
    val nextRemindAt: LocalDateTime? = null,
    val held: Boolean = false
) {
    val hasLocation: Boolean get() = !locLabel.isNullOrBlank()

    /** Time at which the first reminder fires. Anytime-today nudges at 17:00, future anytime days at 09:00. */
    fun effectiveDue(today: LocalDate = LocalDate.now()): LocalDateTime =
        dueAt ?: day.atTime(if (day <= today) 17 else 9, 0)

    fun status(now: LocalDateTime = LocalDateTime.now()): TaskStatus = when (state) {
        TaskState.DONE -> TaskStatus.DONE
        TaskState.SKIPPED -> TaskStatus.SKIPPED
        TaskState.PENDING -> {
            val due = effectiveDue(now.toLocalDate())
            if (now.isBefore(due)) TaskStatus.UPCOMING
            else if (Duration.between(due, now).toMinutes() <= 60) TaskStatus.DUE
            else TaskStatus.OVERDUE
        }
    }

    fun isStale(now: LocalDateTime = LocalDateTime.now()): Boolean =
        state == TaskState.PENDING && now.isAfter(effectiveDue(now.toLocalDate()).plusDays(3))

    fun daysOverdue(now: LocalDateTime = LocalDateTime.now()): Long =
        Duration.between(effectiveDue(now.toLocalDate()), now).toDays()

    fun timeLabel(): String = if (anytime || dueAt == null) "any" else dueAt.toLocalTime().hhmm()

    fun nextOccurrenceDay(): LocalDate? {
        val rule = repeatRule ?: return null
        if (rule == "DAILY") return day.plusDays(1)
        if (rule.startsWith("WEEKLY:")) {
            val days = rule.removePrefix("WEEKLY:").split(",").mapNotNull { parseDow(it.trim()) }
            if (days.isEmpty()) return day.plusWeeks(1)
            var d = day.plusDays(1)
            repeat(8) {
                if (d.dayOfWeek in days) return d
                d = d.plusDays(1)
            }
        }
        return null
    }

    companion object {
        fun parseDow(s: String): DayOfWeek? = when (s.uppercase().take(3)) {
            "MON" -> DayOfWeek.MONDAY; "TUE" -> DayOfWeek.TUESDAY; "WED" -> DayOfWeek.WEDNESDAY
            "THU" -> DayOfWeek.THURSDAY; "FRI" -> DayOfWeek.FRIDAY; "SAT" -> DayOfWeek.SATURDAY
            "SUN" -> DayOfWeek.SUNDAY; else -> null
        }
    }
}

@Entity(tableName = "events")
data class ReminderEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val type: EventType,
    val at: LocalDateTime = LocalDateTime.now(),
    val note: String = ""
)

class Converters {
    @TypeConverter fun ldtToString(v: LocalDateTime?): String? = v?.toString()
    @TypeConverter fun stringToLdt(v: String?): LocalDateTime? = v?.let { LocalDateTime.parse(it) }
    @TypeConverter fun ldToString(v: LocalDate?): String? = v?.toString()
    @TypeConverter fun stringToLd(v: String?): LocalDate? = v?.let { LocalDate.parse(it) }
}

fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)

fun LocalDate.friendly(today: LocalDate = LocalDate.now()): String = when (this) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> {
        val dow = dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        val mon = month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        "$dow $dayOfMonth $mon"
    }
}
