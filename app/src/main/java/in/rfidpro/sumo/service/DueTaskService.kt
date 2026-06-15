package `in`.rfidpro.sumo.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import `in`.rfidpro.sumo.MainActivity
import `in`.rfidpro.sumo.R
import `in`.rfidpro.sumo.SumoApp
import `in`.rfidpro.sumo.api.PentadClient
import `in`.rfidpro.sumo.api.TaskDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Foreground service that polls the backend every 60s for tasks whose
 * dueDate + dueTime just hit, and speaks them via Sarvam TTS.
 *
 * Lifecycle:
 *   - User toggles "Start Reminders" in MainActivity → startForegroundService
 *   - Service starts a single coroutine loop on the IO dispatcher
 *   - On each tick: fetch open tasks, scan for ones due in [lastTick, now],
 *     speak any that aren't already in the "announced today" set
 *   - User taps Stop in MainActivity (or the notification) → stopSelf
 *
 * No mic, no STT — purely outbound TTS. Battery cost ~1-2% daily.
 *
 * dueTime is optional on tasks. Two cases:
 *   - has dueTime: speak when wall clock crosses dueDate+dueTime
 *   - no dueTime: skip (we don't fire all-day tasks as voice reminders;
 *     that's a notification feature for v2 if the user asks)
 */
class DueTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickerJob: Job? = null
    private lateinit var playback: AudioPlayback
    private var lastTick: LocalDateTime = LocalDateTime.now()
    private var lastResetDay: LocalDate = LocalDate.now()

    override fun onCreate() {
        super.onCreate()
        playback = AudioPlayback(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        PentadClient.tokens.remindersEnabled = true
        if (tickerJob == null || tickerJob?.isActive != true) {
            tickerJob = scope.launch { tickerLoop() }
        }
        return START_STICKY
    }

    private suspend fun tickerLoop() {
        // First tick fires almost immediately so users get a quick feel
        // for whether the service is alive.
        var first = true
        while (scope.isActive) {
            if (!first) delay(TICK_INTERVAL_MS) else delay(2_000)
            first = false
            try {
                runOneTick()
            } catch (t: Throwable) {
                // Network blip, auth refresh failure, JSON parse glitch —
                // log and continue. The next tick retries.
                android.util.Log.w(TAG, "tick failed: ${t.message}")
            }
        }
    }

    private suspend fun runOneTick() {
        val tokens = PentadClient.tokens
        if (!tokens.isLoggedIn) return

        // Daily reset of the "already announced" set so a task whose
        // due date rolls over at midnight isn't suppressed.
        val today = LocalDate.now()
        if (today != lastResetDay) {
            tokens.resetAnnounced()
            lastResetDay = today
        }

        val now = LocalDateTime.now()
        val resp = PentadClient.api.tasks(
            statusIn = "pending,in_progress",
            dueDate = today.toString(),
            ordering = "dueDate",
        )
        if (!resp.isSuccessful) {
            android.util.Log.w(TAG, "tasks fetch ${resp.code()}")
            return
        }
        val tasks = resp.body()?.results.orEmpty()

        val lang = tokens.voiceLanguage
        val translateFrom = if (lang == "en-IN") null else "en-IN"

        for (t in tasks) {
            if (tokens.isAnnounced(t.id)) continue
            val taskDueTime = combineDateTime(t.dueDate, t.dueTime) ?: continue
            if (taskDueTime.isAfter(lastTick) && !taskDueTime.isAfter(now)) {
                // Just crossed its due moment this tick.
                val text = "Reminder: ${t.title}. Due now."
                try {
                    playback.speak(text, languageCode = lang, translateFrom = translateFrom)
                    tokens.markAnnounced(t.id)
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "speak failed for #${t.id}: ${e.message}")
                    // Don't mark announced — retry next tick.
                }
            }
        }
        lastTick = now
    }

    /** Combine dueDate (YYYY-MM-DD) + dueTime (HH:MM[:SS]) → LocalDateTime.
     *  Returns null if either is missing or unparseable. */
    private fun combineDateTime(dueDate: String?, dueTime: String?): LocalDateTime? {
        if (dueDate.isNullOrBlank() || dueTime.isNullOrBlank()) return null
        return try {
            val d = LocalDate.parse(dueDate)
            val t = LocalTime.parse(if (dueTime.length == 5) "$dueTime:00" else dueTime)
            LocalDateTime.of(d, t)
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, DueTaskService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, SumoApp.CHANNEL_REMINDERS)
            .setContentTitle(getString(R.string.notif_reminders_title))
            .setContentText(getString(R.string.notif_reminders_text))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        PentadClient.tokens.remindersEnabled = false
        tickerJob?.cancel()
        scope.cancel()
        playback.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "in.rfidpro.sumo.action.STOP"
        private const val NOTIF_ID = 4242
        private const val TAG = "DueTaskService"
        private const val TICK_INTERVAL_MS = 60_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, DueTaskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DueTaskService::class.java))
        }
    }
}
