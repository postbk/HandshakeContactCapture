package com.example.handshakecontactcapture.ai

import com.example.handshakecontactcapture.capture.MemoAudio
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

object TranscriptionContract {
    fun wav(pcm: ByteArray): ByteArray {
        if (pcm.size !in 3200..MemoAudio.MAX_BYTES || pcm.size % 2 != 0)
            throw AiFailure("The saved audio is empty, incomplete, or too long. Record again; your existing audio is retained.")
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
    fun multipart(model: String, pcm: ByteArray, boundary: String): ByteArray {
        require(model.matches(Regex("[a-zA-Z0-9._-]{1,100}")))
        require(boundary.matches(Regex("[a-zA-Z0-9-]{1,100}")))
        return ByteArrayOutputStream().apply {
            fun text(value: String) = write(value.toByteArray(Charsets.UTF_8))
            text("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n")
            text("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\njson\r\n")
            text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"summary.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            write(wav(pcm)); text("\r\n--$boundary--\r\n")
        }.toByteArray()
    }
    fun transcript(response: JSONObject): String {
        val text = response.opt("text")
        if (text !is String || text.isBlank() || text.length > 8000)
            throw AiFailure("OpenAI returned no usable transcript or too much text. Your audio is saved; retry or type the summary.")
        return text.trim()
    }
}
