package `in`.rfidpro.sumo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import `in`.rfidpro.sumo.api.PentadClient

/**
 * Application class — boot-time setup for the API client + notification
 * channel(s). Kept tiny so startup is fast (~50ms cold-start budget).
 *
 * The PentadClient singleton initialises lazily, so the Application
 * just hands it the Application context for SharedPreferences /
 * cookie storage. No network calls fire from here.
 */
class SumoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PentadClient.init(this)
        registerNotificationChannels()
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            getString(R.string.channel_reminders_name),
            // LOW so the persistent foreground notification doesn't ping
            // every time we update it. Spoken reminders themselves play
            // through MediaPlayer, not the notification channel.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_reminders_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_REMINDERS = "sumo.reminders"
    }
}
