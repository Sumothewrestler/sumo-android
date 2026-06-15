package `in`.rfidpro.sumo.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around Android's built-in SpeechRecognizer.
 *
 * One-shot listening:
 *   - call start(lang, onResult, onError)
 *   - on final transcript or error, the recognizer auto-destroys
 *
 * The system picks on-device vs cloud automatically. On Pixel /
 * recent Samsung, English-IN is on-device and instant. Indic
 * languages tend to fall through to cloud STT (Google's).
 *
 * Bluetooth headset routing: when an HFP/A2DP headset is the active
 * audio device, Android automatically routes the mic capture to the
 * headset's microphone. No app-side Bluetooth code needed.
 */
class SpeechCapture(private val ctx: Context) {

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(
        languageTag: String = "en-IN",
        onPartial: (String) -> Unit = {},
        onResult: (String) -> Unit,
        onError: (code: Int, message: String) -> Unit,
    ) {
        stop()  // discard any previous instance — defensive
        if (!isAvailable) {
            onError(-1, "Speech recognition isn't available on this device.")
            return
        }

        val rec = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = rec

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: return
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                cleanup()
                if (text.isBlank()) {
                    onError(
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        "Didn't catch that — please try again.",
                    )
                } else {
                    onResult(text)
                }
            }

            override fun onError(error: Int) {
                cleanup()
                onError(error, errorText(error))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        rec.startListening(intent)
    }

    fun stop() {
        recognizer?.let { rec ->
            try {
                rec.stopListening()
                rec.cancel()
                rec.destroy()
            } catch (_: Throwable) { /* defensive */ }
        }
        recognizer = null
    }

    private fun cleanup() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recogniser error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
        SpeechRecognizer.ERROR_NETWORK -> "Network error — try again."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout — try again."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy — try again."
        SpeechRecognizer.ERROR_SERVER -> "Speech server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try again."
        else -> "Speech error ($code)."
    }
}
