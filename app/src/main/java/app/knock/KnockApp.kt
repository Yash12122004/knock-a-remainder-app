package app.knock

import android.app.Application
import android.content.Context
import app.knock.data.KnockDatabase
import app.knock.data.Repository
import app.knock.data.SettingsStore
import app.knock.reminder.DigestWorker
import app.knock.reminder.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KnockApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val db: KnockDatabase by lazy { KnockDatabase.get(this) }
    val repo: Repository by lazy { Repository(this, db.dao(), settings) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        DigestWorker.enqueue(this)
        appScope.launch { repo.checkMissedAlarms() }
    }

    companion object {
        fun get(ctx: Context): KnockApp = ctx.applicationContext as KnockApp
    }
}
