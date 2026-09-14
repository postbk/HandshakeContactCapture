package com.example.handshakecontactcapture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.*
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.ai.AiFailure
import com.example.handshakecontactcapture.capture.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

class AndroidTranscriberTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeRecognizer(val scope: CoroutineScope, val supportError: Int?, val recognitionError: Int? = null) : MemoSpeechRecognizer {
        lateinit var listener: RecognitionListener
        var starts = 0
        var received = byteArrayOf()
        var cancelled = false
        var destroyed = false
        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
        override fun checkRecognitionSupport(intent: Intent, executor: Executor, callback: RecognitionSupportCallback) {
            if (supportError != null) callback.onError(supportError)
            else callback.onSupportResult(RecognitionSupport.Builder().build())
        }
        override fun startListening(intent: Intent) {
            starts++
            if (recognitionError != null) { listener.onError(recognitionError); return }
            @Suppress("DEPRECATION")
            val input = intent.getParcelableExtra<ParcelFileDescriptor>(RecognizerIntent.EXTRA_AUDIO_SOURCE)!!
            // Duplicate as the remote binder service would; cancellation can close the caller's descriptor.
            val copy = ParcelFileDescriptor.dup(input.fileDescriptor)
            scope.launch(Dispatchers.IO) {
                received = ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
                withContext(Dispatchers.Main) {
                    listener.onSegmentResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Send specifications.")) })
                    listener.onEndOfSegmentedSession()
                }
            }
        }
        override fun cancel() { cancelled = true }
        override fun destroy() { destroyed = true }
    }

    @Test fun unavailableSupportCheckStillTranscribesAllSavedSamples() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context))
        val file = File(context.cacheDir, "fake-transcription.pcm")
        val samples = ByteArray(64000) { (it % 100).toByte() }
        try {
            file.writeBytes(samples)
            for (code in listOf(null, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)) {
                val fake = FakeRecognizer(this, code)
                val result = AndroidMemoTranscriber(context) { fake }.transcribe(file)
                assertEquals("Send specifications.", result)
                assertEquals(1, fake.starts)
                assertArrayEquals(samples, fake.received)
                assertTrue(fake.cancelled && fake.destroyed)
                assertArrayEquals(samples, file.readBytes())
            }
        } finally { file.delete() }
    }

    @Test fun languageAndRecognitionErrorsKeepTheirCauseAndPreserveAudio() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context))
        val file = File(context.cacheDir, "fake-failure.pcm")
        try {
            file.writeBytes(ByteArray(6400))
            val language = FakeRecognizer(this, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            try { AndroidMemoTranscriber(context) { language }.transcribe(file); fail("Missing language accepted") }
            catch (error: AiFailure) { assertTrue(error.message!!.contains("not downloaded")); assertTrue(error.message!!.contains("13")) }
            assertEquals(0, language.starts)
            val network = FakeRecognizer(this, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, SpeechRecognizer.ERROR_NETWORK)
            try { AndroidMemoTranscriber(context) { network }.transcribe(file); fail("Network error accepted") }
            catch (error: AiFailure) { assertTrue(error.message!!.contains("could not connect")); assertFalse(error.message!!.contains("language")) }
            assertEquals(1, network.starts)
            assertEquals(6400L, file.length())
            assertTrue(language.destroyed && network.destroyed)
        } finally { file.delete() }
    }
}
