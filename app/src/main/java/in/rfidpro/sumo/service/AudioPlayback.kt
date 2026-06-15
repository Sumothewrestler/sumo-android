package `in`.rfidpro.sumo.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import `in`.rfidpro.sumo.api.PentadClient
import `in`.rfidpro.sumo.api.SpeakReq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the TTS playback path: POSTs to /api/agent/speak/, streams the
 * returned WAV into a temp file, plays it via MediaPlayer.
 *
 * MediaPlayer is preferred over ExoPlayer here because:
 *   - the audio is short (1-30s)
 *   - we don't need transport controls
 *   - Bluetooth A2DP routing is automatic via AudioManager's active
 *     audio device — no app-side Bluetooth wiring
 *
 * The "speak" suspend function blocks until playback finishes, so
 * callers can sequence multiple reminders cleanly.
 */
class AudioPlayback(private val ctx: Context) {

    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    /**
     * Speak [text] in [languageCode]. Suspends until playback completes
     * or fails. Cancels any in-flight playback before starting.
     *
     * @param translateFrom If set + different from languageCode, the
     *   backend translates first then synthesises.
     */
    suspend fun speak(
        text: String,
        languageCode: String,
        translateFrom: String? = null,
    ) {
        if (text.isBlank()) return
        stop()  // duck any in-flight playback

        val response = PentadClient.api.speak(
            SpeakReq(
                text = text,
                language_code = languageCode,
                translate_from = translateFrom,
            )
        )
        if (!response.isSuccessful) {
            throw IOException("speak ${response.code()} ${response.message()}")
        }
        val body = response.body() ?: throw IOException("speak: empty body")

        val file = withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("sumo_", ".wav", ctx.cacheDir)
            body.byteStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp
        }
        tempFile = file

        playFile(file)
        // Cleanup happens AFTER playback so the file isn't yanked while
        // MediaPlayer is mid-read.
        file.delete()
        tempFile = null
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                player = null
                if (cont.isActive) cont.resume(Unit)
            }
            setOnErrorListener { _, what, extra ->
                player = null
                if (cont.isActive) {
                    cont.resumeWithException(
                        IOException("MediaPlayer error: what=$what extra=$extra")
                    )
                }
                true
            }
            try {
                setDataSource(file.absolutePath)
                prepareAsync()
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
        player = mp

        cont.invokeOnCancellation {
            try { mp.stop() } catch (_: Throwable) {}
            try { mp.release() } catch (_: Throwable) {}
            player = null
        }
    }

    /** Hard-stop. Safe to call from any thread. */
    fun stop() {
        player?.let {
            try { it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        player = null
        tempFile?.delete()
        tempFile = null
    }
}
