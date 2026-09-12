package app.knock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.time.LocalTime

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "knock_settings")

data class Settings(
    val onboardingDone: Boolean = false,
    val intervalHigh: Int = 5,
    val intervalNormal: Int = 15,
    val intervalLow: Int = 60,
    val maxSnoozes: Int = 5,
    val staleDigest: Boolean = true,
    val requireSkipReason: Boolean = true,
    val quietStart: Int = 23 * 60,      // minutes from midnight
    val quietEnd: Int = 7 * 60,
    val highBreaksDnd: Boolean = false,
    val voiceLang: String = "en-IN",
    val confirmBeforeAdd: Boolean = true,
    /** PM_IF_PAST | ALWAYS_PM | ALWAYS_AM */
    val bareNumberRule: String = "PM_IF_PAST",
    val darkTheme: Boolean = true,
    val lastDigestDay: String = "",
    val lastZone: String = "",
    val zoneChangePending: Boolean = false,
    val forceStopWarning: Boolean = false
) {
    fun intervalFor(p: Priority): Int = when (p) {
        Priority.HIGH -> intervalHigh
        Priority.NORMAL -> intervalNormal
        Priority.LOW -> intervalLow
    }

    fun isQuiet(now: LocalDateTime): Boolean {
        if (quietStart == quietEnd) return false
        val m = now.toLocalTime().toSecondOfDay() / 60
        return if (quietStart < quietEnd) m in quietStart until quietEnd
        else m >= quietStart || m < quietEnd
    }

    /** The next moment quiet hours end, relative to now (only meaningful if isQuiet). */
    fun quietEndsAt(now: LocalDateTime): LocalDateTime {
        val end = LocalTime.of(quietEnd / 60, quietEnd % 60)
        val candidate = now.toLocalDate().atTime(end)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    }
}

class SettingsStore(private val context: Context) {
    private object K {
        val onboardingDone = booleanPreferencesKey("onboardingDone")
        val intervalHigh = intPreferencesKey("intervalHigh")
        val intervalNormal = intPreferencesKey("intervalNormal")
        val intervalLow = intPreferencesKey("intervalLow")
        val maxSnoozes = intPreferencesKey("maxSnoozes")
        val staleDigest = booleanPreferencesKey("staleDigest")
        val requireSkipReason = booleanPreferencesKey("requireSkipReason")
        val quietStart = intPreferencesKey("quietStart")
        val quietEnd = intPreferencesKey("quietEnd")
        val highBreaksDnd = booleanPreferencesKey("highBreaksDnd")
        val voiceLang = stringPreferencesKey("voiceLang")
        val confirmBeforeAdd = booleanPreferencesKey("confirmBeforeAdd")
        val bareNumberRule = stringPreferencesKey("bareNumberRule")
        val darkTheme = booleanPreferencesKey("darkTheme")
        val lastDigestDay = stringPreferencesKey("lastDigestDay")
        val lastZone = stringPreferencesKey("lastZone")
        val zoneChangePending = booleanPreferencesKey("zoneChangePending")
        val forceStopWarning = booleanPreferencesKey("forceStopWarning")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        val d = Settings()
        Settings(
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            intervalHigh = p[K.intervalHigh] ?: d.intervalHigh,
            intervalNormal = p[K.intervalNormal] ?: d.intervalNormal,
            intervalLow = p[K.intervalLow] ?: d.intervalLow,
            maxSnoozes = p[K.maxSnoozes] ?: d.maxSnoozes,
            staleDigest = p[K.staleDigest] ?: d.staleDigest,
            requireSkipReason = p[K.requireSkipReason] ?: d.requireSkipReason,
            quietStart = p[K.quietStart] ?: d.quietStart,
            quietEnd = p[K.quietEnd] ?: d.quietEnd,
            highBreaksDnd = p[K.highBreaksDnd] ?: d.highBreaksDnd,
            voiceLang = p[K.voiceLang] ?: d.voiceLang,
            confirmBeforeAdd = p[K.confirmBeforeAdd] ?: d.confirmBeforeAdd,
            bareNumberRule = p[K.bareNumberRule] ?: d.bareNumberRule,
            darkTheme = p[K.darkTheme] ?: d.darkTheme,
            lastDigestDay = p[K.lastDigestDay] ?: d.lastDigestDay,
            lastZone = p[K.lastZone] ?: d.lastZone,
            zoneChangePending = p[K.zoneChangePending] ?: d.zoneChangePending,
            forceStopWarning = p[K.forceStopWarning] ?: d.forceStopWarning
        )
    }

    suspend fun get(): Settings = flow.first()

    suspend fun update(transform: (Settings) -> Settings) {
        val s = transform(get())
        context.dataStore.edit { p ->
            p[K.onboardingDone] = s.onboardingDone
            p[K.intervalHigh] = s.intervalHigh
            p[K.intervalNormal] = s.intervalNormal
            p[K.intervalLow] = s.intervalLow
            p[K.maxSnoozes] = s.maxSnoozes
            p[K.staleDigest] = s.staleDigest
            p[K.requireSkipReason] = s.requireSkipReason
            p[K.quietStart] = s.quietStart
            p[K.quietEnd] = s.quietEnd
            p[K.highBreaksDnd] = s.highBreaksDnd
            p[K.voiceLang] = s.voiceLang
            p[K.confirmBeforeAdd] = s.confirmBeforeAdd
            p[K.bareNumberRule] = s.bareNumberRule
            p[K.darkTheme] = s.darkTheme
            p[K.lastDigestDay] = s.lastDigestDay
            p[K.lastZone] = s.lastZone
            p[K.zoneChangePending] = s.zoneChangePending
            p[K.forceStopWarning] = s.forceStopWarning
        }
    }
}
