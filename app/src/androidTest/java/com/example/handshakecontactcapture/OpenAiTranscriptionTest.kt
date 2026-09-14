package com.example.handshakecontactcapture

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.ai.*
import com.example.handshakecontactcapture.capture.*
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpenAiTranscriptionTest {
    @Test fun waveAndMultipartPreserveSamplesAndValidateOutput() {
        val pcm = ByteArray(6400) { (it % 100).toByte() }
        val wav = TranscriptionContract.wav(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(16000, header.getInt(24))
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        val body = TranscriptionContract.multipart("gpt-4o-mini-transcribe", pcm, "test-boundary")
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"file\"; filename=\"summary.wav\"\r\nContent-Type: audio/wav"))
        assertTrue(text.endsWith("\r\n--test-boundary--\r\n"))
        assertFalse(text.contains("store"))
        assertEquals("Send specifications", TranscriptionContract.transcript(JSONObject().put("text", "Send specifications")))
        for (value in listOf("", " ", "x".repeat(8001), 123)) {
            try { TranscriptionContract.transcript(JSONObject().put("text", value)); fail("Invalid transcript accepted") } catch (_: AiFailure) {}
        }
        try { TranscriptionContract.wav(ByteArray(3201)); fail("Partial sample accepted") } catch (_: AiFailure) {}
    }

    @Test fun queuedTranscriptionPersistsAndLateResultsCannotOverwriteOrResurrect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, HandshakeDatabase::class.java).build()
        try {
            val dao = db.dao()
            val event = Event(name = "Synthetic transcription")
            val person = Contact(name = "Example")
            val encounter = Encounter(eventId = event.id, contactId = person.id, notes = "Keep note", followUp = "Keep task")
            dao.saveEvent(event); dao.saveConnection(person, encounter)
            val record = ConversationSummary(encounter.id, "Existing draft", state = "transcription_queued", model = "fixture", audioPath = "fixture.pcm")
            dao.saveSummary(record)
            var calls = 0
            val provider = object : AiProvider {
                override suspend fun extract(model: String, images: List<File>) = error("Unexpected extraction")
                override suspend fun research(model: String, identity: String) = error("Unexpected research")
                override suspend fun transcribe(model: String, audio: File): String { calls++; assertEquals("fixture.pcm", audio.name); return "Send specifications" }
            }
            val runner = AiJobRunner(dao, PhotoStorage(context), provider, MemoAudio(context))
            runner.transcribe(encounter.id, "stale")
            assertEquals(0, calls)
            runner.transcribe(encounter.id, record.requestId)
            assertEquals("Existing draft\nSend specifications", dao.summary(encounter.id)!!.transcript)
            assertEquals("Send specifications", dao.summary(encounter.id)!!.originalTranscript)
            assertEquals("draft", dao.summary(encounter.id)!!.state)
            runner.transcribe(encounter.id, record.requestId)
            assertEquals(1, calls)
            val correction = record.copy(transcript = "User correction", requestId = newId(), state = "draft")
            dao.saveSummary(correction)
            dao.finishTranscription(encounter.id, record.requestId, "Late text")
            assertEquals("User correction", dao.summary(encounter.id)!!.transcript)
            assertEquals("Keep note", dao.connection(encounter.id)!!.encounter.notes)
            dao.deleteConnection(encounter.id)
            dao.finishTranscription(encounter.id, correction.requestId, "Deleted text")
            assertNull(dao.summary(encounter.id))
        } finally { db.close() }
    }
}
