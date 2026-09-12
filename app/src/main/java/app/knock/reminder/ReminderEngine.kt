package app.knock.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.knock.KnockApp
import app.knock.data.EventType
import app.knock.data.Priority
import app.knock.data.ReminderEvent
import app.knock.data.Task
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- scheduler

class ReminderScheduler(private val ctx: Context) {
    private val am get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pi(): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_FIRE }
        return PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    fun schedule(at: LocalDateTime?) {
        val p = pi()
        am.cancel(p)
        if (at == null) return
        val nowMs = System.currentTimeMillis()
        val ms = maxOf(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), nowMs + 1000)
        try {
            if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        }
    }
}

// ---------------------------------------------------------------- engine

object ReminderEngine {
    /** Called when the single exact alarm fires. Fires everything due, coalescing bursts into a digest. */
    suspend fun onAlarm(ctx: Context) {
        val app = KnockApp.get(ctx)
        val repo = app.repo
        val dao = repo.dao
        val settings = app.settings.get()
        val now = LocalDateTime.now()

        val pending = dao.pending()
        val due = pending.filter { t -> t.nextRemindAt != null && !t.nextRemindAt.isAfter(now) }
        if (due.isEmpty()) { repo.rescheduleAll(); return }

        val quiet = settings.isQuiet(now)
        val toFire = mutableListOf<Task>()
        var anyHeld = false
        for (t in due) {
            if (t.isStale(now)) { dao.update(t.copy(nextRemindAt = null)); continue }
            if (quiet && !(t.priority == Priority.HIGH && settings.highBreaksDnd)) {
                if (!t.held) dao.insertEvent(ReminderEvent(taskId = t.id, type = EventType.HELD, note = "quiet hours"))
                dao.update(t.copy(held = true, nextRemindAt = settings.quietEndsAt(now)))
                continue
            }
            toFire += t
        }
        val heldOvernight = toFire.any { it.held }
        if (heldOvernight) anyHeld = true

        if (toFire.size == 1) {
            val t = toFire.first()
            val interval = t.intervalMin ?: settings.intervalFor(t.priority)
            val updated = t.copy(remindCount = t.remindCount + 1, nextRemindAt = now.plusMinutes(interval.toLong()), held = false)
            dao.update(updated)
            dao.insertEvent(ReminderEvent(taskId = t.id, type = EventType.REMINDED, note = "#${updated.remindCount}"))
            NotificationHelper.showReminder(ctx, updated, updated.remindCount, interval, settings.highBreaksDnd)
        } else if (toFire.size > 1) {
            for (t in toFire) {
                val interval = t.intervalMin ?: settings.intervalFor(t.priority)
                dao.update(t.copy(remindCount = t.remindCount + 1, nextRemindAt = now.plusMinutes(interval.toLong()), held = false))
                dao.insertEvent(ReminderEvent(taskId = t.id, type = EventType.REMINDED, note = "in digest"))
            }
            NotificationHelper.showDigest(ctx, toFire.size, anyHeld)
        }
        repo.rescheduleAll()
    }

    /** Daily stale-task digest (template D). */
    suspend fun staleDigest(ctx: Context) {
        val app = KnockApp.get(ctx)
        val s = app.settings.get()
        if (!s.staleDigest) return
        val today = LocalDate.now().toString()
        if (s.lastDigestDay == today) return
        val now = LocalDateTime.now()
        val stale = app.repo.dao.pending().filter { it.isStale(now) }
        if (stale.isEmpty()) return
        stale.forEach { NotificationHelper.showStale(ctx, it, it.daysOverdue(now)) }
        app.settings.update { it.copy(lastDigestDay = today) }
    }
}

// ---------------------------------------------------------------- receivers

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "app.knock.FIRE"
        const val ACTION_DONE = "app.knock.DONE"
        const val ACTION_SNOOZE = "app.knock.SNOOZE"
        const val ACTION_DROP = "app.knock.DROP"
        const val ACTION_MOVE_ALL = "app.knock.MOVE_ALL"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_MINUTES = "minutes"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val repo = KnockApp.get(context).repo
        val id = intent.getLongExtra(EXTRA_TASK_ID, -1)
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> ReminderEngine.onAlarm(context)
                    ACTION_DONE -> repo.markDone(id)
                    ACTION_SNOOZE -> {
                        val mins = intent.getIntExtra(EXTRA_MINUTES, 10)
                        val ok = repo.snooze(id, LocalDateTime.now().plusMinutes(mins.toLong()))
                        if (!ok) {
                            // out of snoozes → open the reminder screen to reschedule or skip
                            val i = Intent(context, app.knock.ReminderActivity::class.java).apply {
                                putExtra(app.knock.ReminderActivity.EXTRA_TASK_ID, id)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(i)
                        }
                    }
                    ACTION_DROP -> repo.skip(id, "Dropped from digest")
                    ACTION_MOVE_ALL -> repo.moveAllToToday()
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = KnockApp.get(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    val s = app.settings.get()
                    val nowZone = ZoneId.systemDefault().id
                    if (s.lastZone.isNotBlank() && s.lastZone != nowZone) app.settings.update { it.copy(zoneChangePending = true) }
                    else app.settings.update { it.copy(lastZone = nowZone) }
                }
                app.repo.rescheduleAll()
                DigestWorker.enqueue(context)
            } finally {
                pending.finish()
            }
        }
    }
}

// ---------------------------------------------------------------- geofences

object GeofenceManager {
    private const val REQ = 77

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun pi(ctx: Context): PendingIntent {
        val i = Intent(ctx, GeofenceReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }

    /** Re-registers geofences for the given location tasks. Silent no-op without permission. */
    suspend fun sync(ctx: Context, tasks: List<Task>) {
        if (!hasPermission(ctx)) return
        try {
            val client = LocationServices.getGeofencingClient(ctx)
            client.removeGeofences(pi(ctx)).await()
            if (tasks.isEmpty()) return
            val fences = tasks.map { t ->
                Geofence.Builder()
                    .setRequestId(t.id.toString())
                    .setCircularRegion(t.locLat!!, t.locLng!!, t.locRadius.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                    .build()
            }
            val req = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences)
                .build()
            client.addGeofences(req, pi(ctx)).await()
        } catch (e: Exception) {
            // Play services missing or permission revoked — time fallback still works.
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences?.mapNotNull { it.requestId.toLongOrNull() } ?: return
        val pending = goAsync()
        val app = KnockApp.get(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { id ->
                    val t = app.repo.dao.get(id) ?: return@forEach
                    if (t.state != app.knock.data.TaskState.PENDING) return@forEach
                    app.repo.dao.insertEvent(ReminderEvent(taskId = id, type = EventType.NEARBY, note = t.locLabel ?: ""))
                    NotificationHelper.showNearby(context, t)
                }
            } finally { pending.finish() }
        }
    }
}

// ---------------------------------------------------------------- worker

class DigestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        ReminderEngine.staleDigest(applicationContext)
        KnockApp.get(applicationContext).repo.rescheduleAll()
        return Result.success()
    }

    companion object {
        fun enqueue(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DigestWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(Duration.ofMinutes(15))
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("knock_digest", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
