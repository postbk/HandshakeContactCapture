package com.example.handshakecontactcapture

import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.*
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import com.example.handshakecontactcapture.capture.AndroidMemoTranscriber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/** Opt-in provider diagnostics using generated speech, never a user's recording. */
class AndroidSpeechProbeTest {
    @Test fun transcribesSyntheticSavedSpeech() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("speechProbe") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Google's service requires this even for file input. Grant only to the disposable verification app.
        require(context.packageName.endsWith(".verification"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val wav = instrumentation.context.assets.open("summary-speech.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var pcm: ByteArray? = null
        while (offset + 8 <= wav.size) {
            val chunk = String(wav, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8 + size <= wav.size)
            if (chunk == "fmt ") {
                assertEquals(1, buffer.getShort(offset + 8).toInt())
                assertEquals(1, buffer.getShort(offset + 10).toInt())
                assertEquals(16000, buffer.getInt(offset + 12))
                assertEquals(16, buffer.getShort(offset + 22).toInt())
            }
            if (chunk == "data") pcm = wav.copyOfRange(offset + 8, offset + 8 + size)
            offset += 8 + size + size % 2
        }
        val file = File(context.cacheDir, "synthetic-speech.pcm")
        try {
            file.writeBytes(requireNotNull(pcm))
            val result = withTimeout(20_000) { AndroidMemoTranscriber(context).transcribe(file) }
            Log.i("HandshakeSpeechProbe", "synthetic-result=$result")
            assertTrue(result.lowercase().contains("specifications"))
            assertTrue(result.lowercase().contains("alex"))
        } finally { file.delete() }
        Unit
    }

    @Test fun reportProviderSupport() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("speechProbe") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                val outcome = withTimeout(15_000) {
                    suspendCancellableCoroutine<String> { continuation ->
                        recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                            override fun onError(error: Int) { if (continuation.isActive) continuation.resume("support-error=$error") }
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (continuation.isActive) continuation.resume("support-ok installed=${support.installedOnDeviceLanguages} online=${support.onlineLanguages}")
                            }
                        })
                    }
                }
                Log.i("HandshakeSpeechProbe", outcome)
                Unit
            } finally { recognizer.destroy(); pipe.forEach { it.close() } }
        }
    }
}
