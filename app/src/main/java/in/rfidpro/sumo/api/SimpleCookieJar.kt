package `in`.rfidpro.sumo.api

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal cookie jar that persists cookies to SharedPreferences so they
 * survive app restarts. Replaces the unmaintained franmontiel/
 * PersistentCookieJar library (its JitPack artifact started 403-ing on
 * 2026-06-15, blocking the CI build).
 *
 * Scope is intentionally small — we only need to round-trip the backend's
 * httpOnly refresh cookie (`pentad_refresh`, path=/api/auth/). The
 * implementation handles general OkHttp cookies correctly so it'd work
 * for any cookie the server hands us; it just doesn't try to be a full
 * RFC 6265 implementation (no public-suffix list, no domain-matching
 * edge cases beyond what OkHttp's `Cookie.matches()` provides).
 *
 * Storage shape — single SharedPreferences string keyed by host:
 *   "<host>" -> JSONArray of cookie blobs
 *
 * On every `saveFromResponse` we merge incoming cookies with the stored
 * set, replacing any same-name same-path cookies. Expired cookies are
 * pruned on read. That keeps the persisted file small without needing
 * a separate cleanup pass.
 */
class SimpleCookieJar(ctx: Context) : CookieJar {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val existing = readFor(host).toMutableList()
        for (incoming in cookies) {
            // Replace any cookie with the same name + path; keeps the
            // set deterministic instead of accumulating duplicates.
            existing.removeAll { it.name == incoming.name && it.path == incoming.path }
            existing.add(incoming)
        }
        writeFor(host, existing)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val all = readFor(url.host)
        // Drop expired cookies + return only those whose domain / path /
        // secure flag match the outgoing URL.
        val live = all.filter { it.expiresAt > now }
        if (live.size != all.size) writeFor(url.host, live)
        return live.filter { it.matches(url) }
    }

    /** Wipe everything — called on logout. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // ----- Storage helpers ------------------------------------------

    private fun readFor(host: String): List<Cookie> {
        val raw = prefs.getString(host, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { cookieFromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeFor(host: String, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            prefs.edit().remove(host).apply()
            return
        }
        val arr = JSONArray()
        for (c in cookies) arr.put(cookieToJson(c))
        prefs.edit().putString(host, arr.toString()).apply()
    }

    private fun cookieToJson(c: Cookie): JSONObject = JSONObject().apply {
        put("name", c.name)
        put("value", c.value)
        put("expiresAt", c.expiresAt)
        put("domain", c.domain)
        put("path", c.path)
        put("secure", c.secure)
        put("httpOnly", c.httpOnly)
        put("hostOnly", c.hostOnly)
        put("persistent", c.persistent)
    }

    private fun cookieFromJson(o: JSONObject): Cookie {
        // Rebuild via OkHttp's Cookie.Builder. The builder mirrors every
        // field except hostOnly + persistent — those are inferred from
        // whether you call hostOnlyDomain() vs domain(), and from the
        // expiry timestamp.
        val builder = Cookie.Builder()
            .name(o.getString("name"))
            .value(o.getString("value"))
            .expiresAt(o.getLong("expiresAt"))
            .path(o.getString("path"))
        val domain = o.getString("domain")
        if (o.optBoolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
        if (o.optBoolean("secure")) builder.secure()
        if (o.optBoolean("httpOnly")) builder.httpOnly()
        return builder.build()
    }

    companion object {
        private const val PREFS_NAME = "sumo_cookies"
    }
}
