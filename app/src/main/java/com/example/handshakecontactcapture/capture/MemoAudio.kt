package com.example.handshakecontactcapture.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import kotlinx.coroutines.*
import java.io.File

/** Raw mono PCM keeps completed samples playable even if the process stops before finalization. */
class MemoAudio(private val context: Context) {
    companion object { const val SAMPLE_RATE = 16000; const val MAX_BYTES = SAMPLE_RATE * 2 * 59 }
    @Volatile private var stopRequested = false
    fun file(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9-]+\\.pcm")))
        return File(File(context.filesDir, "memos"), name)
    }
    fun stop() { stopRequested = true }
    fun reset() { stopRequested = false }
    @SuppressLint("MissingPermission") // Requested by the UI immediately before recording.
    suspend fun record(name: String, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        val destination = file(name)
        destination.parentFile!!.mkdirs()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone format unavailable" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            writeSamples(name, progress) { bytes, limit -> recorder.read(bytes, 0, limit) }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
    /** The same bounded writer is exercised with synthetic samples in device tests. */
    suspend fun writeSamples(name: String, progress: (Int) -> Unit, read: (ByteArray, Int) -> Int) = withContext(Dispatchers.IO) {
        val destination = file(name)
        destination.parentFile!!.mkdirs()
        destination.outputStream().use { output ->
            val bytes = ByteArray(2048)
            var count = 0
            while (!stopRequested && count < MAX_BYTES) {
                currentCoroutineContext().ensureActive()
                val limit = minOf(bytes.size, MAX_BYTES - count)
                val received = read(bytes, limit)
                check(received in 1..limit) { "Microphone interrupted" }
                output.write(bytes, 0, received)
                count += received
                progress(count / (SAMPLE_RATE * 2))
            }
        }
    }
    suspend fun play(name: String) = withContext(Dispatchers.IO) {
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, 4096)).setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            player.play()
            var total = 0
            file(name).inputStream().use { input ->
                val bytes = ByteArray(2048)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(bytes)
                    if (count <= 0) break
                    var offset = 0
                    while (offset < count) {
                        val written = player.write(bytes, offset, count - offset)
                        check(written > 0)
                        offset += written
                    }
                    total += count
                }
            }
            while (player.playbackHeadPosition < total / 2) { delay(30) }
        } finally { runCatching { player.stop() }; player.release() }
    }
}
