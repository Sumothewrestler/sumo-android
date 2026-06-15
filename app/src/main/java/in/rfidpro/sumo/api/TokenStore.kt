package `in`.rfidpro.sumo.api

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences-backed store for:
 *   - JWT access token (15-min TTL — refreshed on 401 via AuthInterceptor)
 *   - the chosen voice language (en-IN / hi-IN / ta-IN / …)
 *   - the "reminders enabled" toggle so the foreground service auto-
 *     restarts after a phone reboot (BootReceiver, future v2).
 *
 * The httpOnly refresh cookie is handled separately by
 * PersistentCookieJar (see PentadClient) — we never touch it directly.
 *
 * NOT encrypted. v1 trusts the device. EncryptedSharedPreferences is a
 * Phase 3 hardening item.
 */
class TokenStore(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ACCESS_TOKEN) else putString(KEY_ACCESS_TOKEN, value)
                apply()
            }
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    /** Voice language for both STT (mic input) and TTS (Sarvam output). */
    var voiceLanguage: String
        get() = prefs.getString(KEY_VOICE_LANG, "en-IN") ?: "en-IN"
        set(value) {
            prefs.edit().putString(KEY_VOICE_LANG, value).apply()
        }

    /** Whether the user enabled spoken reminders. Used by BootReceiver
     *  to decide whether to restart the foreground service. */
    var remindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDERS_ON, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REMINDERS_ON, value).apply()
        }

    /** Set of task ids we've already announced today. The DueTaskService
     *  consults this so a task isn't spoken twice. */
    fun isAnnounced(taskId: Long): Boolean =
        prefs.getStringSet(KEY_ANNOUNCED, emptySet())?.contains(taskId.toString()) == true

    fun markAnnounced(taskId: Long) {
        val set = (prefs.getStringSet(KEY_ANNOUNCED, emptySet()) ?: emptySet()).toMutableSet()
        set.add(taskId.toString())
        prefs.edit().putStringSet(KEY_ANNOUNCED, set).apply()
    }

    /** Called by DueTaskService at midnight or on first-tick of a new
     *  day so yesterday's announcements don't suppress today's. */
    fun resetAnnounced() {
        prefs.edit().remove(KEY_ANNOUNCED).apply()
    }

    /** Logout: clears everything except voice language (UX preference). */
    fun clearAuth() {
        prefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_USERNAME)
            remove(KEY_ANNOUNCED)
            remove(KEY_REMINDERS_ON)
            apply()
        }
    }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    companion object {
        private const val PREFS_NAME = "sumo_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_VOICE_LANG = "voice_lang"
        private const val KEY_REMINDERS_ON = "reminders_on"
        private const val KEY_ANNOUNCED = "announced_today"
    }
}
