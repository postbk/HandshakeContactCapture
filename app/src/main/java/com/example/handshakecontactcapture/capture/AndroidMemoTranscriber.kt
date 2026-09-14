package com.example.handshakecontactcapture.capture

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.*
import com.example.handshakecontactcapture.ai.AiFailure
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Narrow platform seam lets regression tests exercise callbacks and the actual PCM pipe. */
interface MemoSpeechRecognizer {
    fun setRecognitionListener(listener: RecognitionListener)
    fun checkRecognitionSupport(intent: Intent, executor: Executor, callback: RecognitionSupportCallback)
    fun startListening(intent: Intent)
    fun cancel()
    fun destroy()
}

private class PlatformMemoSpeechRecognizer(context: Context) : MemoSpeechRecognizer {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    override fun setRecognitionListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
    @androidx.annotation.RequiresApi(33)
    override fun checkRecognitionSupport(intent: Intent, executor: Executor, callback: RecognitionSupportCallback) =
        recognizer.checkRecognitionSupport(intent, executor, callback)
    override fun startListening(intent: Intent) = recognizer.startListening(intent)
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()
}

object SpeechFailureMessages {
    fun message(code: Int, checkingSupport: Boolean = false): String {
        val reason = when (code) {
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Android's speech provider does not support the selected device language. Change its speech language or use keyboard voice typing."
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Android's speech language is not downloaded or ready. Download it in the device's speech settings, then retry."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android's speech provider needs microphone permission. Allow microphone access in app settings, then retry."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Android's speech provider could not connect. Check your connection and retry."
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Android did not recognize speech in this recording. Play it back to check the sound, then retry or type the summary."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Android's speech provider is busy. Close other voice input and try again shortly."
            SpeechRecognizer.ERROR_AUDIO -> "Android's speech provider could not read the recording. Play it back to check the sound, or use keyboard voice typing."
            else -> "Android's speech provider failed ${if (checkingSupport) "its compatibility check" else "to transcribe the recording"}. Retry or use keyboard voice typing."
        }
        return "$reason Your audio is saved. (Android speech error $code)"
    }
}

class AndroidMemoTranscriber(private val context: Context,
    private val createRecognizer: (Context) -> MemoSpeechRecognizer = { PlatformMemoSpeechRecognizer(it) }) {
    suspend fun transcribe(file: File): String = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isRecognitionAvailable(context))
            throw AiFailure("Saved-audio transcription is unavailable. Use keyboard voice typing or type the summary; your audio is saved.")
        withTimeout(90_000) {
            val pipe = withContext(Dispatchers.IO) {
                if (!file.exists() || file.length() < 3200) throw AiFailure("The recording is empty or too short. Record again or type a summary.")
                ParcelFileDescriptor.createPipe()
            }
            var recognizer: MemoSpeechRecognizer? = null
            var feeder: Job? = null
            try {
                val session = createRecognizer(context).also { recognizer = it }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MemoAudio.SAMPLE_RATE)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                suspendCancellableCoroutine { continuation ->
                    val segments = mutableListOf<String>()
                    fun fail(error: Int, checkingSupport: Boolean = false) {
                        if (continuation.isActive) continuation.resumeWithException(AiFailure(SpeechFailureMessages.message(error, checkingSupport)))
                    }
                    fun collect(bundle: Bundle?) {
                        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }?.let { segments += it }
                    }
                    fun finish() {
                        if (segments.isEmpty()) fail(SpeechRecognizer.ERROR_NO_MATCH)
                        else if (continuation.isActive) continuation.resume(segments.joinToString(" ").take(8000))
                    }
                    session.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) = fail(error)
                        override fun onResults(results: Bundle?) { collect(results); finish() }
                        override fun onSegmentResults(segmentResults: Bundle) { collect(segmentResults) }
                        override fun onEndOfSegmentedSession() = finish()
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    var started = false
                    fun start() {
                        if (!continuation.isActive || started) return
                        started = true
                        try {
                            session.startListening(intent)
                            feeder = launch(Dispatchers.IO) {
                                try { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                                    file.inputStream().use { it.copyTo(output) }
                                } } catch (_: Exception) { withContext(Dispatchers.Main) { fail(SpeechRecognizer.ERROR_AUDIO) } }
                            }
                        } catch (_: SecurityException) { fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
                        catch (_: Exception) { fail(SpeechRecognizer.ERROR_CLIENT) }
                    }
                    session.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onError(error: Int) {
                            // Error 14 means the optional check is unavailable, not that recognition is unsupported.
                            if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) start()
                            else fail(error, checkingSupport = true)
                        }
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) = start()
                    })
                }
            } finally {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
                withContext(NonCancellable + Dispatchers.IO) { pipe.forEach { runCatching { it.close() } } }
                feeder?.cancel()
            }
        }
    }
}
