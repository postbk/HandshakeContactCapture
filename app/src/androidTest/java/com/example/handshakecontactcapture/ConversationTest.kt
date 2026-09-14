package com.example.handshakecontactcapture

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.ai.*
import com.example.handshakecontactcapture.capture.PhotoStorage
import com.example.handshakecontactcapture.capture.MemoAudio
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConversationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val transcript = "Send specifications to Alex next week. They build inspection robots."
    private val input = JSONObject().put("sources", JSONArray(listOf("https://example.test/products"))).toString()
    private fun result(kind: String = "follow_up", sources: List<String> = emptyList()) = JSONObject()
        .put("suggestions", JSONArray().put(JSONObject().put("kind", kind)
            .put("text", "Send the promised specifications")
            .put("evidence", "Send specifications to Alex next week.")
            .put("recipient", "Alex").put("timing", "next week").put("sources", JSONArray(sources)))).toString()

    @Test fun validatesEvidenceUnknownsAndResearchSourcesWithoutTools() {
        val request = SummaryContract.request("gpt-4.1-mini", transcript, input)
        assertFalse(request.has("tools"))
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
        val parsed = SummaryContract.parse(result(), transcript, input).single()
        assertEquals("next week", parsed.timing)
        val nullable = JSONObject(result()).apply {
            getJSONArray("suggestions").getJSONObject(0).put("recipient", JSONObject.NULL).put("timing", JSONObject.NULL)
        }
        assertNull(SummaryContract.parse(nullable.toString(), transcript, input).single().recipient)
        SummaryContract.parse(result("research_alignment", listOf("https://example.test/products")), transcript, input)
        fun rejected(json: String) {
            try { SummaryContract.response(AiFixtures.response(json), transcript, input); fail("Invalid output accepted") }
            catch (_: AiFailure) {}
        }
        rejected(result().replace("Send specifications to Alex next week.", "A fabricated quotation"))
        rejected(result().replace("\"next week\"", "\"2026-09-21\""))
        rejected(result("research_alignment", listOf("https://invented.test")))
        rejected(result("research_conflict"))
        rejected(result("unsupported_kind"))
        rejected(JSONObject(result()).apply { getJSONArray("suggestions").getJSONObject(0).put("recipient", 5) }.toString())
        rejected(JSONObject().put("suggestions", JSONArray((0..16).map { JSONObject(result()).getJSONArray("suggestions").get(0) })).toString())
    }

    @Test fun summaryFormattingDifferencesKeepOriginalEvidenceAndBadRowsDoNotDiscardGoodOnes() {
        val original = "Send specifications to ALEX\nnext   week. They build inspection robots."
        val good = JSONObject(result()).getJSONArray("suggestions").getJSONObject(0)
        val bad = JSONObject(good.toString()).put("evidence", "Invented commitment")
        val response = JSONObject().put("suggestions", JSONArray().put(good).put(bad)).toString()
        val validated = SummaryContract.response(AiFixtures.response(response), original, input)
        val accepted = SummaryContract.parse(validated, original, input).single()
        assertEquals("Send specifications to ALEX\nnext   week.", accepted.evidence)
        assertEquals("ALEX", accepted.recipient)
        assertEquals("next   week", accepted.timing)
        assertEquals(1, JSONObject(validated).getJSONArray("validation_warnings").length())
        val quote = JSONObject(good.toString()).put("evidence", "They said \"we're interested\".")
            .put("recipient", JSONObject.NULL).put("timing", "")
        val parsed = SummaryContract.parse(JSONObject().put("suggestions", JSONArray().put(quote)).toString(),
            "They said “we’re interested”.", input).single()
        assertEquals("They said “we’re interested”.", parsed.evidence)
        assertNull(parsed.timing)
    }

    @Test fun reviewedSuggestionsPreserveEditsAreIdempotentAndIgnoreStaleResults() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HandshakeDatabase::class.java).build()
        try {
            val dao = database.dao()
            val event = Event(name = "Synthetic summary event")
            val contact = Contact(name = "Alex", company = "Example")
            val encounter = Encounter(eventId = event.id, contactId = contact.id, notes = "Existing note", followUp = "Old task", done = true)
            dao.saveEvent(event); dao.saveConnection(contact, encounter)
            val record = ConversationSummary(encounter.id, transcript, transcript, state = "queued", context = input, model = "fixture")
            dao.saveSummary(record)
            var called = 0
            val provider = object : AiProvider {
                override suspend fun extract(model: String, images: List<File>) = error("Unexpected photo request")
                override suspend fun research(model: String, identity: String) = error("Private transcript must not be searched")
                override suspend fun summarize(model: String, transcript: String, context: String): String { called++; return result() }
            }
            val runner = AiJobRunner(dao, PhotoStorage(context), provider)
            runner.summarize(encounter.id, "stale")
            assertEquals(0, called)
            runner.summarize(encounter.id, record.requestId)
            assertEquals("review", dao.summary(encounter.id)!!.state)
            dao.saveEncounter(encounter.copy(notes = "User correction"))
            dao.applySummary(encounter.id, record.requestId, 0, "Reviewed task", true)
            dao.applySummary(encounter.id, record.requestId, 0, "Duplicate", true)
            val saved = dao.connection(encounter.id)!!.encounter
            assertEquals("Old task\n\nReviewed task", saved.followUp)
            assertEquals("User correction", saved.notes)
            assertFalse(saved.done)
            val updated = record.copy(transcript = "Corrected text", requestId = newId(), state = "draft")
            dao.saveSummary(updated)
            assertEquals(0, dao.finishSummary(encounter.id, record.requestId, "review", result(), "", 1))
            assertEquals("Corrected text", dao.summary(encounter.id)!!.transcript)
            dao.deleteConnection(encounter.id)
            assertNull(dao.summary(encounter.id))
            assertEquals(0, dao.finishSummary(encounter.id, updated.requestId, "review", result(), "", 1))
        } finally { database.close() }
    }

    @Test fun versionTwoResearchAndCapturesSurviveSummaryMigration() = runBlocking {
        val name = "summary-migration-${newId()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val schema = instrumentation.context.assets.open("com.example.handshakecontactcapture.data.HandshakeDatabase/2.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                schema.getJSONArray("entities").objects().forEach { entity ->
                    old.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                    entity.optJSONArray("indices")?.objects()?.forEach { index ->
                        old.execSQL(index.getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
                old.execSQL("INSERT INTO events VALUES ('event','Fixture','','',1)")
                old.execSQL("INSERT INTO contacts VALUES ('person','Alex','Example','','','','','')")
                old.execSQL("INSERT INTO encounters VALUES ('meeting','event','person','Keep note','Keep task',1,1,'capture','Printed')")
                old.execSQL("INSERT INTO captures VALUES ('capture','event','[]','review','{}','','fixture',1)")
                old.execSQL("INSERT INTO research VALUES ('meeting','identity','ready','Saved research','[]','','fixture',1,'request')")
                old.version = 2
            }
            val db = Room.databaseBuilder(context, HandshakeDatabase::class.java, name).addMigrations(HandshakeDatabase.MIGRATION_2_3, HandshakeDatabase.MIGRATION_3_4).build()
            try {
                assertEquals("Keep note", db.dao().connection("meeting")!!.encounter.notes)
                assertEquals("Saved research", db.dao().researchFor("meeting")!!.summary)
                assertEquals("review", db.dao().capture("capture")!!.state)
                assertTrue(db.dao().summaries().first().isEmpty())
                db.dao().saveSummary(ConversationSummary("meeting", "Persisted summary", audioPath = "fixture.pcm"))
            } finally { db.close() }
            val reopened = Room.databaseBuilder(context, HandshakeDatabase::class.java, name).build()
            try { assertEquals("fixture.pcm", reopened.dao().summary("meeting")!!.audioPath) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun audioPathsCannotEscapePrivateStorage() {
        val storage = MemoAudio(context)
        assertEquals(1888000, MemoAudio.MAX_BYTES)
        try { storage.file("../secrets.pcm"); fail("Unsafe path") } catch (_: IllegalArgumentException) {}
    }

    @Test fun audioWriterStopsAt59SecondsAndRetainsSamplesOnInterruption() = runBlocking {
        val storage = MemoAudio(context)
        val name = "${newId()}.pcm"
        var seconds = 0
        try {
            storage.writeSamples(name, { seconds = it }) { bytes, limit -> bytes.fill(1); limit }
            assertEquals(59, seconds)
            assertEquals(MemoAudio.MAX_BYTES.toLong(), storage.file(name).length())
            var reads = 0
            try {
                storage.writeSamples(name, {}) { _, limit -> if (++reads == 3) -1 else limit }
                fail("Interrupted read accepted")
            } catch (_: IllegalStateException) {}
            assertEquals(4096L, storage.file(name).length())
            // A newly created storage instance can still access samples after interruption.
            assertEquals(4096L, MemoAudio(context).file(name).length())
            storage.reset()
            storage.writeSamples(name, {}) { _, limit -> storage.stop(); limit }
            assertEquals(2048L, storage.file(name).length())
        } finally { storage.file(name).delete() }
    }
}
