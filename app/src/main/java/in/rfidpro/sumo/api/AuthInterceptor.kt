package `in`.rfidpro.sumo.api

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * Attaches Authorization: Bearer <accessToken> to every request, and
 * on a 401 makes one synchronous attempt to refresh via /api/auth/refresh/.
 *
 * The refresh token rides as an httpOnly cookie attached automatically
 * by the PersistentCookieJar (configured in PentadClient). We don't
 * see or store it directly — that's the whole point of the cookie jar.
 *
 * Refresh response shape (per backend core/auth/views.py):
 *   { "accessToken": "<new-jwt>" }
 *
 * If refresh itself returns 401 the user is fully signed out — we wipe
 * the access token; MainActivity / LoginActivity see `isLoggedIn = false`
 * on the next render and route to login.
 *
 * Skipping the refresh on the /auth/login/ + /auth/refresh/ endpoints
 * themselves so we don't recursively call refresh while refreshing.
 */
class AuthInterceptor(private val tokens: TokenStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        if (path.startsWith("/api/auth/login") ||
            path.startsWith("/api/auth/refresh")
        ) {
            return chain.proceed(request)
        }

        val firstResponse = chain.proceed(addAuthHeader(request))
        if (firstResponse.code != 401) return firstResponse

        // 401 — try a single refresh. Body must be consumed before we
        // can issue another request on the same call chain.
        firstResponse.close()

        val refreshed = tryRefresh(chain) ?: run {
            tokens.clearAuth()
            // Let the original 401 propagate so the caller (UI) can
            // route to LoginActivity.
            return chain.proceed(addAuthHeader(request))
        }
        tokens.accessToken = refreshed
        return chain.proceed(addAuthHeader(request))
    }

    private fun addAuthHeader(request: Request): Request {
        val token = tokens.accessToken ?: return request
        return request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }

    /** POST /api/auth/refresh/ — refresh cookie auto-attached. Returns
     *  the new accessToken, or null on any failure. */
    private fun tryRefresh(chain: Interceptor.Chain): String? {
        val refreshReq = Request.Builder()
            .url(chain.request().url.newBuilder().encodedPath("/api/auth/refresh/").build())
            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
            .build()
        return try {
            chain.proceed(refreshReq).use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.string()?.let { JSONObject(it).optString("accessToken").takeIf { s -> s.isNotBlank() } }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
