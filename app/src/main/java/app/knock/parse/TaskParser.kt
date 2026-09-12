package app.knock.parse

import app.knock.data.Priority
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month

data class ParsedTask(
    val title: String,
    val day: LocalDate,
    val time: LocalTime?,
    val anytime: Boolean,
    val priority: Priority,
    val tag: String,
    val locLabel: String?,
    val warnings: List<String>,
    val original: String,
    /** true when the time was rolled to tomorrow because it had already passed today */
    val rolled: Boolean = false
) {
    val dueAt: LocalDateTime? get() = time?.let { day.atTime(it) }
    val parseNote: String get() = warnings.joinToString("; ")
}

object TaskParser {

    private val wordNumbers = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12
    )
    private val months = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL,
        "may" to Month.MAY, "jun" to Month.JUNE, "jul" to Month.JULY, "aug" to Month.AUGUST,
        "sep" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER
    )
    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )
    private val tagRules: List<Pair<Regex, String>> = listOf(
        Regex("\\b(bank|pay|bill|rent|money|invoice|tax|salary|emi|loan)\\b") to "Money",
        Regex("\\b(gym|run|workout|yoga|doctor|dentist|medicine|meds|pills|walk|swim)\\b") to "Health",
        Regex("\\b(assignment|meeting|report|email|submit|deadline|project|presentation|slides|standup|boss|client)\\b") to "Work",
        Regex("\\b(pick up|parcel|buy|groceries|store|shop|post office|library|return|courier|order)\\b") to "Errands",
        Regex("\\b(clean|laundry|cook|dishes|trash|garbage|plants|water)\\b") to "Home",
        Regex("\\b(mom|dad|mum|wife|husband|brother|sister|grandma|grandpa|friend|birthday|call|text|message)\\b") to "Personal"
    )
    private val locationPhrases: List<Pair<Regex, String?>> = listOf(
        Regex("\\bon (?:the|my) way (?:back )?home\\b") to "Home",
        Regex("\\bon (?:the|my) way to (?:the )?(work|office)\\b") to "Work",
        Regex("\\bwhen (?:i(?:'m| am)? )?(?:near|at|passing) (?:the )?([a-z' ]+?)(?=\\s+(?:at|by|on|tomorrow|today|tonight)\\b|$)") to null,
        Regex("\\bnear (?:the )?([a-z' ]+?)(?=\\s+(?:at|by|on|tomorrow|today|tonight)\\b|$)") to null,
        Regex("\\b(?:at|from|to) the (post office|store|shop|mall|market|pharmacy|supermarket|grocery store|chemist|office)\\b") to null
    )

    fun split(text: String): List<String> =
        text.split(Regex("\\s*(?:,|;|\\.\\s|\\band then\\b|\\bthen\\b|\\band\\b|\\balso\\b)\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now(), bareRule: String = "PM_IF_PAST"): List<ParsedTask> {
        val chunks = split(text)
        val tasks = chunks.map { parseChunk(it, now, bareRule) }.toMutableList()
        // same-time hints
        val groups = tasks.groupBy { it.day to it.time }
        return tasks.map { t ->
            val same = t.time != null && (groups[t.day to t.time]?.size ?: 0) > 1
            if (same) t.copy(warnings = t.warnings + "Same time as another task — reorder or adjust") else t
        }
    }

    fun parseChunk(raw: String, now: LocalDateTime, bareRule: String): ParsedTask {
        val original = raw.trim()
        var s = " " + original.lowercase().replace(Regex("\\s+"), " ") + " "
        val warnings = mutableListOf<String>()
        val today = now.toLocalDate()
        var day: LocalDate = today
        var explicitDay = false
        var time: LocalTime? = null
        var dayPartDefault: LocalTime? = null
        var priority = Priority.NORMAL

        // ----- day phrases (before times, so "in 3 days" isn't read as 3 o'clock)
        fun consume(regex: Regex, action: (MatchResult) -> Unit): Boolean {
            val m = regex.find(s) ?: return false
            action(m); s = s.replaceRange(m.range, " "); return true
        }
        if (consume(Regex("\\b(?:the )?day after tomorrow\\b")) { day = today.plusDays(2); explicitDay = true }) Unit
        else if (consume(Regex("\\btomorrow\\b")) { day = today.plusDays(1); explicitDay = true }) Unit
        consume(Regex("\\bin (\\d+) days?\\b")) { day = today.plusDays(it.groupValues[1].toLong()); explicitDay = true }
        consume(Regex("\\bin a week\\b|\\bnext week\\b")) { day = today.plusWeeks(1); explicitDay = true }
        consume(Regex("\\b(?:(next|this) )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")) { m ->
            val dow = weekdays[m.groupValues[2]]!!
            var d = today.plusDays(1)
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            if (m.groupValues[1] == "next" && d.minusDays(7).isAfter(today)) d = d.minusDays(7)
            day = d; explicitDay = true
        }
        consume(Regex("\\b(?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: of)? (jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b")) { m ->
            resolveDate(today, m.groupValues[1].toInt(), months[m.groupValues[2]]!!)?.let { day = it; explicitDay = true }
        }
        consume(Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]* (\\d{1,2})(?:st|nd|rd|th)?\\b")) { m ->
            resolveDate(today, m.groupValues[2].toInt(), months[m.groupValues[1]]!!)?.let { day = it; explicitDay = true }
        }
        consume(Regex("\\btoday\\b")) { explicitDay = true }
        consume(Regex("\\btonight\\b")) { dayPartDefault = LocalTime.of(20, 0); explicitDay = true }
        consume(Regex("\\b(?:in the |this )?(morning|afternoon|evening|night|noon|midday|midnight|lunch(?:time)?)\\b")) { m ->
            dayPartDefault = when (m.groupValues[1]) {
                "morning" -> LocalTime.of(9, 0); "afternoon" -> LocalTime.of(15, 0)
                "evening" -> LocalTime.of(18, 0); "night" -> LocalTime.of(21, 0)
                "noon", "midday" -> LocalTime.NOON; "midnight" -> LocalTime.MIDNIGHT
                else -> LocalTime.of(13, 0)
            }
        }

        // ----- clock times
        val timeRe = Regex("(?:\\b(at|by|around|before|@)\\s*)?\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.|o'clock)?\\b")
        val wordRe = Regex("\\b(at|by|around|before)\\s+(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\b(?:\\s*(am|pm))?")
        val tm = timeRe.findAll(s).firstOrNull { m ->
            m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty() || m.groupValues[4].isNotEmpty()
        }
        if (tm != null) {
            val h = tm.groupValues[2].toInt()
            val min = tm.groupValues[3].ifEmpty { "0" }.toInt()
            val ap = tm.groupValues[4].replace(".", "")
            if (h in 0..24 && min in 0..59) {
                time = resolveHour(h, min, ap, day, now, bareRule, warnings)
                s = s.replaceRange(tm.range, " ")
            }
        } else {
            wordRe.find(s)?.let { m ->
                time = resolveHour(wordNumbers[m.groupValues[2]]!!, 0, m.groupValues[3], day, now, bareRule, warnings)
                s = s.replaceRange(m.range, " ")
            }
        }
        if (time == null && dayPartDefault != null) {
            time = dayPartDefault
            warnings += "Assumed ${time!!.hhmm()} for that part of the day"
        }
        // a time in the past today → roll
        var rolled = false
        if (time != null && day == today && !explicitDay && day.atTime(time!!).isBefore(now)) {
            day = today.plusDays(1); rolled = true
            warnings += "${time!!.hhmm()} already passed — moved to tomorrow. Keep today?"
        }
        if (time == null && day.isBefore(today)) day = today

        // ----- location
        var locLabel: String? = null
        for ((re, fixed) in locationPhrases) {
            val m = re.find(s) ?: continue
            locLabel = fixed ?: m.groupValues.getOrNull(1)?.trim()?.replaceFirstChar { it.uppercase() }
            if (fixed != null || re.pattern.startsWith("\\bwhen") || re.pattern.startsWith("\\bnear")) {
                s = s.replaceRange(m.range, " ")
            }
            break
        }
        if (locLabel != null) {
            warnings += "Location reminder (near $locLabel) — time fallback set" +
                    (if (time == null) ", 17:00 today" else "")
        }

        // ----- priority
        if (consume(Regex("\\b(urgent(?:ly)?|important|asap|must|critical)\\b")) { priority = Priority.HIGH }) Unit
        else if (consume(Regex("\\b(low priority|whenever|sometime|no rush|if possible)\\b")) { priority = Priority.LOW }) Unit

        // ----- tag
        var tag = ""
        for ((re, t) in tagRules) if (re.containsMatchIn(s)) { tag = t; break }

        // ----- title cleanup
        var title = s.trim()
            .replace(Regex("^(?:i (?:need|have|want) to|need to|have to|remind me to|remember to|to|please)\\s+"), "")
            .replace(Regex("\\s+(?:at|by|on|in|for|around|before|the|a|an|to|from|is|due)$"), "")
            .replace(Regex("^(?:at|by|on|in|for|around|before|the|a|an|to|and)\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.isEmpty()) { title = "Untitled task"; warnings += "Couldn't find a title — edit it" }
        title = title.replaceFirstChar { it.uppercase() }
        if (title.length > 80) warnings += "Long title — it will be clamped to two lines"

        val anytime = time == null
        if (anytime) warnings += if (day == today) "No time — anytime today, first nudge 17:00" else "No time — first nudge 09:00 that day"
        if (locLabel != null && tag.isEmpty()) tag = "Errands"

        return ParsedTask(title, day, time, anytime, priority, tag, locLabel, warnings, original, rolled)
    }

    private fun resolveDate(today: LocalDate, dayOfMonth: Int, month: Month): LocalDate? = try {
        var d = LocalDate.of(today.year, month, dayOfMonth)
        if (d.isBefore(today)) d = d.plusYears(1)
        d
    } catch (e: Exception) { null }

    private fun resolveHour(h: Int, min: Int, ap: String, day: LocalDate, now: LocalDateTime, bareRule: String, warnings: MutableList<String>): LocalTime {
        var hour = h % 24
        when {
            ap == "am" -> if (hour == 12) hour = 0
            ap == "pm" -> if (hour < 12) hour += 12
            hour == 0 || hour > 12 -> Unit // unambiguous 24h
            else -> {
                val amReading = LocalTime.of(hour, min)
                val amPassed = day == now.toLocalDate() && day.atTime(amReading).isBefore(now)
                val pm = when (bareRule) {
                    "ALWAYS_PM" -> true
                    "ALWAYS_AM" -> false
                    else -> amPassed || hour in 1..6
                }
                if (pm && hour != 12) hour += 12
                if (!pm && hour == 12) hour = 0
                warnings += "Assumed ${if (pm) "PM" else "AM"} for \"$h\"" + if (amPassed && pm) " (AM already passed)" else ""
            }
        }
        return LocalTime.of(hour, min)
    }

    private fun LocalTime.hhmm() = "%02d:%02d".format(hour, minute)
}
