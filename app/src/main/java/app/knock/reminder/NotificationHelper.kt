package app.knock.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.knock.MainActivity
import app.knock.R
import app.knock.ReminderActivity
import app.knock.data.Priority
import app.knock.data.Task
import app.knock.data.hhmm

object NotificationHelper {
    const val CH_REMINDERS = "reminders"
    const val CH_HIGH = "reminders_high"
    const val CH_DIGEST = "digest"
    const val CH_NEARBY = "nearby"
    const val DIGEST_ID = 900_001
    const val STALE_ID = 900_002
    const val NEARBY_BASE = 500_000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val reminders = NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Due-now reminders that repeat until you mark the task done"
            enableVibration(true); setSound(alarmSound, attrs)
        }
        val high = NotificationChannel(CH_HIGH, "High-priority reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Can bypass Do Not Disturb if you allow it in system settings"
            enableVibration(true); setSound(alarmSound, attrs); setBypassDnd(true)
        }
        val digest = NotificationChannel(CH_DIGEST, "Digests", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Catch-up and stale-task summaries"
        }
        val nearby = NotificationChannel(CH_NEARBY, "Nearby reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Location-triggered reminders"
        }
        nm.createNotificationChannels(listOf(reminders, high, digest, nearby))
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun flags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun reminderActivityIntent(ctx: Context, taskId: Long, openSkip: Boolean = false): PendingIntent {
        val i = Intent(ctx, ReminderActivity::class.java).apply {
            putExtra(ReminderActivity.EXTRA_TASK_ID, taskId)
            putExtra(ReminderActivity.EXTRA_OPEN_SKIP, openSkip)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(ctx, (taskId * 10 + (if (openSkip) 1 else 0)).toInt(), i, flags())
    }

    private fun actionIntent(ctx: Context, action: String, taskId: Long, requestSalt: Int, extra: (Intent) -> Unit = {}): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            extra(this)
        }
        return PendingIntent.getBroadcast(ctx, (taskId * 10 + requestSalt).toInt(), i, flags())
    }

    private fun mainIntent(ctx: Context, route: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(ctx, route.hashCode(), i, flags())
    }

    /** Template A: due-now persistent reminder with full-screen intent. */
    fun showReminder(ctx: Context, task: Task, reminderNo: Int, nextInMin: Int, bypassDnd: Boolean) {
        if (!canPost(ctx)) return
        val channel = if (bypassDnd && task.priority == Priority.HIGH) CH_HIGH else CH_REMINDERS
        val due = task.effectiveDue().toLocalTime().hhmm()
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText("Due $due · Reminder $reminderNo · next in $nextInMin min")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setAutoCancel(false)
            .setContentIntent(reminderActivityIntent(ctx, task.id))
            .setFullScreenIntent(reminderActivityIntent(ctx, task.id), true)
            .addAction(0, "Done", actionIntent(ctx, ReminderReceiver.ACTION_DONE, task.id, 2))
            .addAction(0, "Snooze 10 min", actionIntent(ctx, ReminderReceiver.ACTION_SNOOZE, task.id, 3) { it.putExtra(ReminderReceiver.EXTRA_MINUTES, 10) })
            .addAction(0, "Skip", reminderActivityIntent(ctx, task.id, openSkip = true))
            .build()
        NotificationManagerCompat.from(ctx).notify(task.id.toInt(), n)
    }

    /** Template B: catch-up digest. */
    fun showDigest(ctx: Context, count: Int, heldOvernight: Boolean) {
        if (!canPost(ctx)) return
        val title = if (heldOvernight) "$count reminders held overnight" else "$count reminders waiting"
        val n = NotificationCompat.Builder(ctx, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Review them or move everything to today")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(ctx, "home"))
            .addAction(0, "Review", mainIntent(ctx, "home"))
            .addAction(0, "Move all to today", actionIntent(ctx, ReminderReceiver.ACTION_MOVE_ALL, 0, 4))
            .build()
        NotificationManagerCompat.from(ctx).notify(DIGEST_ID, n)
    }

    /** Template C: nearby location trigger. */
    fun showNearby(ctx: Context, task: Task) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_NEARBY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("You're near ${task.locLabel}")
            .setContentText(task.title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(reminderActivityIntent(ctx, task.id))
            .addAction(0, "Done", actionIntent(ctx, ReminderReceiver.ACTION_DONE, task.id, 2))
            .addAction(0, "Snooze 10 min", actionIntent(ctx, ReminderReceiver.ACTION_SNOOZE, task.id, 3) { it.putExtra(ReminderReceiver.EXTRA_MINUTES, 10) })
            .build()
        NotificationManagerCompat.from(ctx).notify((NEARBY_BASE + task.id).toInt(), n)
    }

    /** Template D: stale task (3+ days overdue) daily digest, one per task. */
    fun showStale(ctx: Context, task: Task, daysOverdue: Long) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Still doing \"${task.title}\"?")
            .setContentText("$daysOverdue days overdue")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(ctx, "task/${task.id}"))
            .addAction(0, "Reschedule", mainIntent(ctx, "task/${task.id}"))
            .addAction(0, "Done", actionIntent(ctx, ReminderReceiver.ACTION_DONE, task.id, 2))
            .addAction(0, "Drop it", actionIntent(ctx, ReminderReceiver.ACTION_DROP, task.id, 5))
            .build()
        NotificationManagerCompat.from(ctx).notify((STALE_ID + task.id * 7).toInt(), n)
    }

    fun cancel(ctx: Context, taskId: Long) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.cancel(taskId.toInt())
        nm.cancel((NEARBY_BASE + taskId).toInt())
        nm.cancel((STALE_ID + taskId * 7).toInt())
    }
}
