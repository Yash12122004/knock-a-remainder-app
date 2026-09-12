package app.knock.data

import android.content.Context
import android.location.Geocoder
import app.knock.parse.ParsedTask
import app.knock.reminder.GeofenceManager
import app.knock.reminder.NotificationHelper
import app.knock.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class Repository(private val context: Context, val dao: TaskDao, val settings: SettingsStore) {
    private val scheduler = ReminderScheduler(context)
    private val spawned = HashMap<Long, Long>()

    fun observeTasks(): Flow<List<Task>> = dao.observeAll()
    fun observeTask(id: Long): Flow<Task?> = dao.observe(id)
    fun observeEvents(id: Long): Flow<List<ReminderEvent>> = dao.observeEvents(id)
    suspend fun get(id: Long): Task? = dao.get(id)

    private suspend fun log(taskId: Long, type: EventType, note: String = "") =
        dao.insertEvent(ReminderEvent(taskId = taskId, type = type, note = note))

    suspend fun addParsed(list: List<ParsedTask>): List<Long> {
        val ids = list.map { p ->
            addTask(
                Task(
                    title = p.title, day = p.day, dueAt = p.dueAt, anytime = p.anytime,
                    priority = p.priority, tag = p.tag, locLabel = p.locLabel,
                    parseNote = "Heard: \"${p.original}\"" + if (p.warnings.isEmpty()) "" else " · " + p.parseNote
                ), created = "from voice/text"
            )
        }
        return ids
    }

    suspend fun addTask(task: Task, created: String = "manually"): Long {
        val now = LocalDateTime.now()
        var t = task.copy(nextRemindAt = maxOf(task.effectiveDue(), now))
        if (t.hasLocation && t.locLat == null) t = geocode(t)
        val id = dao.insert(t)
        log(id, EventType.CREATED, created + (t.parseNote?.let { " · $it" } ?: ""))
        rescheduleAll()
        return id
    }

    suspend fun update(task: Task) {
        val old = dao.get(task.id)
        var t = task
        val timeChanged = old != null && (old.day != t.day || old.dueAt != t.dueAt || old.anytime != t.anytime)
        if (timeChanged) {
            t = t.copy(nextRemindAt = maxOf(t.effectiveDue(), LocalDateTime.now()), held = false, snoozeCount = 0)
            log(t.id, EventType.RESCHEDULED, "to ${t.day.friendly()} ${t.timeLabel()}")
        }
        if (t.hasLocation && (t.locLat == null || old?.locLabel != t.locLabel)) t = geocode(t)
        if (!t.hasLocation) t = t.copy(locLat = null, locLng = null)
        dao.update(t)
        rescheduleAll()
    }

    suspend fun rescheduleTo(id: Long, day: LocalDate, time: LocalTime?) {
        val t = dao.get(id) ?: return
        update(t.copy(day = day, dueAt = time?.let { day.atTime(it) }, anytime = time == null, state = TaskState.PENDING, skipReason = null))
    }

    suspend fun markDone(id: Long): Task? {
        val t = dao.get(id) ?: return null
        val now = LocalDateTime.now()
        dao.update(t.copy(state = TaskState.DONE, doneAt = now, nextRemindAt = null, held = false))
        log(id, EventType.DONE)
        NotificationHelper.cancel(context, id)
        spawnNext(t)?.let { spawned[id] = it }
        rescheduleAll()
        return t
    }

    suspend fun undoDone(id: Long) {
        val t = dao.get(id) ?: return
        dao.update(t.copy(state = TaskState.PENDING, doneAt = null, nextRemindAt = maxOf(t.effectiveDue(), LocalDateTime.now())))
        spawned.remove(id)?.let { sid -> dao.get(sid)?.let { dao.deleteEvents(sid); dao.delete(it) } }
        rescheduleAll()
    }

    suspend fun snooze(id: Long, until: LocalDateTime): Boolean {
        val t = dao.get(id) ?: return false
        val s = settings.get()
        val today = LocalDate.now()
        val count = if (t.snoozeDay == today) t.snoozeCount else 0
        if (count >= s.maxSnoozes) return false
        dao.update(t.copy(nextRemindAt = until, snoozeCount = count + 1, snoozeDay = today, held = false))
        log(id, EventType.SNOOZED, "until ${until.toLocalTime().hhmm()}")
        NotificationHelper.cancel(context, id)
        rescheduleAll()
        return true
    }

    suspend fun snoozesLeft(t: Task): Int {
        val s = settings.get()
        val count = if (t.snoozeDay == LocalDate.now()) t.snoozeCount else 0
        return (s.maxSnoozes - count).coerceAtLeast(0)
    }

    suspend fun skip(id: Long, reason: String, allFuture: Boolean = false) {
        val t = dao.get(id) ?: return
        val keepRule = if (allFuture) null else t.repeatRule
        dao.update(t.copy(state = TaskState.SKIPPED, skipReason = reason, nextRemindAt = null, held = false, repeatRule = keepRule))
        log(id, EventType.SKIPPED, reason)
        NotificationHelper.cancel(context, id)
        if (!allFuture) spawnNext(t)
        rescheduleAll()
    }

    suspend fun delete(id: Long) {
        val t = dao.get(id) ?: return
        NotificationHelper.cancel(context, id)
        dao.deleteEvents(id)
        dao.delete(t)
        rescheduleAll()
    }

    private suspend fun spawnNext(t: Task): Long? {
        val nextDay = t.nextOccurrenceDay() ?: return null
        val next = t.copy(
            id = 0, day = nextDay, dueAt = t.dueAt?.toLocalTime()?.let { nextDay.atTime(it) },
            state = TaskState.PENDING, doneAt = null, skipReason = null, remindCount = 0, snoozeCount = 0,
            snoozeDay = null, held = false, createdAt = LocalDateTime.now(), nextRemindAt = null, parseNote = null
        )
        val id = dao.insert(next.copy(nextRemindAt = next.effectiveDue()))
        log(id, EventType.CREATED, "recurring from ${t.day.friendly()}")
        return id
    }

    /** Ensures every pending task has a next reminder time, then programs the single exact alarm. */
    suspend fun rescheduleAll() {
        val now = LocalDateTime.now()
        val pending = dao.pending().map { t ->
            if (t.isStale(now)) {
                if (t.nextRemindAt != null) dao.update(t.copy(nextRemindAt = null)); t.copy(nextRemindAt = null)
            } else if (t.nextRemindAt == null) {
                val fixed = t.copy(nextRemindAt = maxOf(t.effectiveDue(now.toLocalDate()), now)); dao.update(fixed); fixed
            } else t
        }
        val next = pending.mapNotNull { it.nextRemindAt }.minOrNull()
        scheduler.schedule(next)
        GeofenceManager.sync(context, pending.filter { it.hasLocation && it.locLat != null })
    }

    suspend fun moveAllToToday() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        dao.pending().filter { it.status(now) == TaskStatus.OVERDUE || it.held }.forEach { t ->
            val newDue = t.dueAt?.toLocalTime()?.let { today.atTime(it) }
            dao.update(t.copy(day = today, dueAt = newDue, nextRemindAt = now.plusMinutes(1), held = false))
            log(t.id, EventType.RESCHEDULED, "moved to today")
        }
        rescheduleAll()
    }

    /** Shift every pending due time by the offset difference between the previous and current zone. */
    suspend fun applyZoneChange(keepLocalTime: Boolean) {
        val s = settings.get()
        if (!keepLocalTime && s.lastZone.isNotBlank()) {
            val now = LocalDateTime.now()
            val oldOff = ZoneId.of(s.lastZone).rules.getOffset(now).totalSeconds
            val newOff = ZoneId.systemDefault().rules.getOffset(now).totalSeconds
            val diff = (newOff - oldOff).toLong()
            dao.pending().forEach { t ->
                if (t.dueAt != null) {
                    val shifted = t.dueAt.plusSeconds(diff)
                    dao.update(t.copy(dueAt = shifted, day = shifted.toLocalDate(), nextRemindAt = null))
                }
            }
        }
        settings.update { it.copy(zoneChangePending = false, lastZone = ZoneId.systemDefault().id) }
        rescheduleAll()
    }

    /** Heuristic force-stop detection: an alarm that should have fired long ago but no reminder happened. */
    suspend fun checkMissedAlarms() {
        val now = LocalDateTime.now()
        val missed = dao.pending().any { t -> t.nextRemindAt != null && t.nextRemindAt.isBefore(now.minusMinutes(10)) && !t.held }
        if (missed) settings.update { it.copy(forceStopWarning = true) }
        val zone = ZoneId.systemDefault().id
        val s = settings.get()
        if (s.lastZone.isBlank()) settings.update { it.copy(lastZone = zone) }
        rescheduleAll()
    }

    // ---------- progress ----------

    /** Days where every due task was done. One grace day per rolling week. Days without tasks are neutral. */
    suspend fun streak(all: List<Task> = dao.all()): Int {
        val today = LocalDate.now()
        val byDay = all.filter { it.state != TaskState.SKIPPED }.groupBy { it.day }
        var d = today
        val todayTasks = byDay[today].orEmpty()
        if (todayTasks.isNotEmpty() && todayTasks.any { it.state != TaskState.DONE }) d = today.minusDays(1)
        var streak = 0
        var graceLeft = 1
        var counted = 0
        var guard = 0
        while (guard++ < 365) {
            val ts = byDay[d].orEmpty()
            if (ts.isNotEmpty()) {
                val ok = ts.all { it.state == TaskState.DONE }
                if (ok) streak++ else if (graceLeft > 0) { graceLeft--; streak++ } else break
                counted++
                if (counted % 7 == 0) graceLeft = 1
            }
            d = d.minusDays(1)
            if (d.isBefore(today.minusDays(400))) break
        }
        return streak
    }

    // ---------- demo + backup ----------

    suspend fun loadDemo() {
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val nextMon = today.plusDays(((DayOfWeek.MONDAY.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }.toLong())
        val nextFri = today.plusDays(((DayOfWeek.FRIDAY.value - today.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }.toLong())
        fun t(title: String, day: LocalDate, time: LocalTime?, p: Priority = Priority.NORMAL, tag: String = "", loc: String? = null, rule: String? = null) =
            Task(title = title, day = day, dueAt = time?.let { day.atTime(it) }, anytime = time == null, priority = p, tag = tag, locLabel = loc, repeatRule = rule)
        val demo = listOf(
            t("Morning run", today, LocalTime.of(8, 0), tag = "Health").copy(state = TaskState.DONE, doneAt = today.atTime(8, 25)),
            t("Call bank", today, LocalTime.of(10, 30), Priority.HIGH, "Money"),
            t("Lunch with Priya", today, LocalTime.of(13, 0), tag = "Personal").copy(state = TaskState.DONE, doneAt = today.atTime(13, 5)),
            t("Submit assignment draft", today, LocalTime.of(14, 0), Priority.HIGH, "Work"),
            t("Pick up parcel", today, LocalTime.of(16, 30), tag = "Errands", loc = "Post office"),
            t("Gym", today, LocalTime.of(18, 0), tag = "Health", rule = "WEEKLY:MON,WED,FRI"),
            t("Call mom", today, LocalTime.of(21, 0), Priority.LOW, "Personal"),
            t("Dentist", nextMon, LocalTime.of(11, 0), Priority.HIGH, "Health"),
            t("Return library books", nextMon, null, tag = "Errands"),
            t("Pay electricity bill", nextFri, LocalTime.of(10, 0), tag = "Money")
        )
        demo.forEach { task ->
            val id = dao.insert(task.copy(nextRemindAt = if (task.state == TaskState.PENDING) maxOf(task.effectiveDue(), now) else null))
            log(id, EventType.CREATED, "demo data")
        }
        rescheduleAll()
    }

    suspend fun exportJson(): String {
        val tasks = JSONArray()
        dao.all().forEach { t ->
            tasks.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("day", t.day.toString()); put("dueAt", t.dueAt?.toString())
                put("anytime", t.anytime); put("priority", t.priority.name); put("tag", t.tag); put("notes", t.notes)
                put("repeatRule", t.repeatRule); put("locLabel", t.locLabel); put("locLat", t.locLat); put("locLng", t.locLng)
                put("locRadius", t.locRadius); put("intervalMin", t.intervalMin); put("state", t.state.name)
                put("createdAt", t.createdAt.toString()); put("doneAt", t.doneAt?.toString()); put("skipReason", t.skipReason)
                put("parseNote", t.parseNote)
            })
        }
        val events = JSONArray()
        dao.allEvents().forEach { e ->
            events.put(JSONObject().apply { put("taskId", e.taskId); put("type", e.type.name); put("at", e.at.toString()); put("note", e.note) })
        }
        return JSONObject().put("version", 1).put("exportedAt", LocalDateTime.now().toString()).put("tasks", tasks).put("events", events).toString(2)
    }

    suspend fun importJson(json: String): Int {
        val root = JSONObject(json)
        val tasks = root.getJSONArray("tasks")
        val events = root.optJSONArray("events") ?: JSONArray()
        val idMap = HashMap<Long, Long>()
        for (i in 0 until tasks.length()) {
            val o = tasks.getJSONObject(i)
            val t = Task(
                title = o.getString("title"), day = LocalDate.parse(o.getString("day")),
                dueAt = o.optString("dueAt", "").takeIf { it.isNotBlank() && it != "null" }?.let { LocalDateTime.parse(it) },
                anytime = o.optBoolean("anytime", false), priority = Priority.valueOf(o.optString("priority", "NORMAL")),
                tag = o.optString("tag", ""), notes = o.optString("notes", ""),
                repeatRule = o.optString("repeatRule", "").takeIf { it.isNotBlank() && it != "null" },
                locLabel = o.optString("locLabel", "").takeIf { it.isNotBlank() && it != "null" },
                locLat = if (o.isNull("locLat")) null else o.optDouble("locLat"),
                locLng = if (o.isNull("locLng")) null else o.optDouble("locLng"),
                locRadius = o.optInt("locRadius", 200),
                intervalMin = if (o.isNull("intervalMin")) null else o.optInt("intervalMin"),
                state = TaskState.valueOf(o.optString("state", "PENDING")),
                createdAt = LocalDateTime.parse(o.optString("createdAt", LocalDateTime.now().toString())),
                doneAt = o.optString("doneAt", "").takeIf { it.isNotBlank() && it != "null" }?.let { LocalDateTime.parse(it) },
                skipReason = o.optString("skipReason", "").takeIf { it.isNotBlank() && it != "null" },
                parseNote = o.optString("parseNote", "").takeIf { it.isNotBlank() && it != "null" }
            )
            idMap[o.optLong("id", -1)] = dao.insert(t)
        }
        for (i in 0 until events.length()) {
            val o = events.getJSONObject(i)
            val newId = idMap[o.optLong("taskId", -1)] ?: continue
            dao.insertEvent(ReminderEvent(taskId = newId, type = EventType.valueOf(o.optString("type", "CREATED")),
                at = LocalDateTime.parse(o.optString("at", LocalDateTime.now().toString())), note = o.optString("note", "")))
        }
        rescheduleAll()
        return tasks.length()
    }

    suspend fun clearAll() {
        dao.all().forEach { NotificationHelper.cancel(context, it.id) }
        dao.clearEvents(); dao.clearTasks(); rescheduleAll()
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(t: Task): Task = withContext(Dispatchers.IO) {
        try {
            if (!Geocoder.isPresent()) return@withContext t
            val results = Geocoder(context, Locale.getDefault()).getFromLocationName(t.locLabel ?: "", 1)
            val a = results?.firstOrNull() ?: return@withContext t
            t.copy(locLat = a.latitude, locLng = a.longitude)
        } catch (e: Exception) { t }
    }
}
