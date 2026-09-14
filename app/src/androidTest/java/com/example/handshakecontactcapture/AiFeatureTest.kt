package com.example.handshakecontactcapture

import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.ai.*
import com.example.handshakecontactcapture.capture.PhotoStorage
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

object AiFixtures {
    fun extraction(): String = JSONObject().put("warnings", JSONArray()).put("candidates", JSONArray().put(
        JSONObject().put("name", "Alex Example").put("company", "Example Robotics")
            .put("title", JSONObject.NULL).put("emails", JSONArray(listOf("alex@example.test", "info@example.test")))
            .put("phones", JSONArray(listOf("Office: +1 202 555 0100", "Mobile: +1 202 555 0101")))
            .put("websites", JSONArray(listOf("https://example.test"))).put("addresses", JSONArray())
            .put("printed_claims", "Industrial inspection robots").put("evidence", "Page 1: Example Robotics")
            .put("warnings", JSONArray(listOf("Job title unreadable")))
    ).put(JSONObject().put("name", JSONObject.NULL).put("company", "Example Parts")
        .put("title", JSONObject.NULL).put("emails", JSONArray()).put("phones", JSONArray())
        .put("websites", JSONArray()).put("addresses", JSONArray()).put("printed_claims", JSONObject.NULL)
        .put("evidence", "Page 2: Example Parts").put("warnings", JSONArray()))).toString()

    fun response(text: String, citations: JSONArray = JSONArray(), search: Boolean = false): JSONObject {
        val output = JSONArray()
        if (search) output.put(JSONObject().put("type", "web_search_call").put("status", "completed"))
        output.put(JSONObject().put("type", "message").put("content", JSONArray().put(
            JSONObject().put("type", "output_text").put("text", text).put("annotations", citations))))
        return JSONObject().put("status", "completed").put("output", output)
    }
}

class AiFeatureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun extractionPreservesRepeatedFieldsAndUnknowns() {
        val result = AiContract.extraction(AiFixtures.response(AiFixtures.extraction()))
        val candidates = AiContract.candidates(result)
        assertEquals(2, candidates.size)
        assertEquals("", candidates[0].contact.title)
        assertEquals(2, candidates[0].contact.email.lines().size)
        assertTrue(candidates[0].contact.phone.contains("+1 202 555 0101"))
        assertTrue(candidates[1].contact.name.isBlank())
        val request = AiContract.extractionRequest("gpt-4.1-mini", listOf("data:image/jpeg;base64,AA=="))
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
    }

    @Test fun incompleteAndMalformedOutputAreRejected() {
        try {
            AiContract.extraction(JSONObject().put("status", "incomplete"))
            fail("Incomplete response was accepted")
        } catch (_: AiFailure) { }
        val invalid = JSONObject(AiFixtures.extraction())
        invalid.getJSONArray("candidates").getJSONObject(0).put("phones", "not an array")
        try {
            AiContract.extraction(AiFixtures.response(invalid.toString()))
            fail("Malformed field was accepted")
        } catch (_: AiFailure) { }
    }

    @Test fun researchRequiresSearchAndSafeCitations() {
        val text = "The company builds robots. [source]"
        val citations = JSONArray().put(JSONObject().put("type", "url_citation")
            .put("start_index", 27).put("end_index", text.length)
            .put("url", "https://example.test/about").put("title", "Example company"))
        val result = AiContract.research(AiFixtures.response(text, citations, true))
        assertEquals(text, result.text)
        assertEquals("https://example.test/about", result.citations.single().url)
        assertEquals(result.citations, AiContract.citations(AiContract.citationsJson(result.citations)))
        try {
            AiContract.research(AiFixtures.response("Remembered company facts without sources"))
            fail("Unsourced research was accepted")
        } catch (_: AiFailure) { }
        assertFalse(validWebUrl("javascript:alert(1)"))
        assertFalse(validWebUrl("file:///private"))
    }

    @Test fun keyIsEncryptedAndRemovable() {
        val name = "test-key-${newId()}"
        val settings = AiSettings(context, name)
        val marker = "sk-local-fixture-not-a-real-key"
        try {
            settings.save(marker, "gpt-4.1-mini", "gpt-4.1")
            assertTrue(settings.hasKey)
            assertEquals(marker, settings.readKey())
            assertFalse(context.getSharedPreferences(name, 0).all.toString().contains(marker))
            settings.remove()
            assertFalse(settings.hasKey)
            assertEquals("", settings.readKey())
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("handshake.$name") }
        }
    }

    @Test fun photoImportNormalizesOrientationAndRemovesLocationMetadata() {
        val source = File(context.cacheDir, "test-photo-${newId()}.jpg")
        val storage = PhotoStorage(context)
        var imported: String? = null
        try {
            val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(38.0, -77.0)
                saveAttributes()
            }
            imported = storage.import(Uri.fromFile(source))
            val normalized = ExifInterface(storage.file(imported))
            assertNull(normalized.latLong)
            val preview = storage.preview(imported)!!
            assertTrue(preview.width > preview.height)
            preview.recycle()
        } finally { source.delete(); imported?.let { storage.delete(it) } }
    }

    @Test fun processingAndRepeatedReviewPreserveEditsAndDeletion() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HandshakeDatabase::class.java).build()
        try {
            val dao = database.dao()
            val event = Event(name = "Synthetic AI event")
            dao.saveEvent(event)
            val capture = Capture(eventId = event.id, state = "queued", model = "fixture")
            dao.insertCapture(capture)
            val provider = object : AiProvider {
                override suspend fun extract(model: String, images: List<File>) = AiFixtures.extraction()
                override suspend fun research(model: String, identity: String) =
                    ResearchResult("Robots [1]", listOf(Citation(7, 10, "https://example.test", "Example")))
            }
            val runner = AiJobRunner(dao, PhotoStorage(context), provider)
            runner.extract(capture.id)
            assertEquals("review", dao.capture(capture.id)!!.state)
            val contact = AiContract.candidates(dao.capture(capture.id)!!.result)[0].contact.copy(id = "${capture.id}-0")
            val encounter = Encounter(id = contact.id, eventId = event.id, contactId = contact.id,
                notes = "PRIVATE MEETING NOTE", captureId = capture.id, printedClaims = "Industrial robots")
            dao.acceptCandidate(capture.id, contact, encounter)
            dao.saveConnection(contact.copy(name = "User correction"), encounter)
            dao.acceptCandidate(capture.id, contact, encounter)
            assertEquals(1, dao.connections().first().size)
            assertEquals("User correction", dao.connection(encounter.id)!!.contact.name)
            val identity = AiContract.researchIdentity(contact.company, contact.website, "", encounter.printedClaims)
            assertFalse(AiContract.researchRequest("fixture", identity).toString().contains("PRIVATE MEETING NOTE"))
            val research = Research(encounter.id, identity, model = "fixture")
            dao.saveResearch(research)
            runner.research(encounter.id, research.requestId)
            assertEquals("ready", dao.researchFor(encounter.id)!!.state)
            dao.deleteConnection(encounter.id)
            assertNull(dao.researchFor(encounter.id))
            assertEquals(0, dao.finishResearch(encounter.id, research.requestId, "ready", "late result", "[]", "", 0))
            dao.deleteCapture(capture.id)
            runner.extract(capture.id)
            assertNull(dao.capture(capture.id))
        } finally { database.close() }
    }

    @Test fun staleResearchCannotReplaceNewerRequest() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, HandshakeDatabase::class.java).build()
        try {
            val dao = database.dao()
            val event = Event(name = "Fixture")
            val person = Contact(company = "Example")
            val encounter = Encounter(eventId = event.id, contactId = person.id)
            dao.saveEvent(event)
            dao.saveConnection(person, encounter)
            val latest = Research(encounter.id, "new identity")
            dao.saveResearch(latest)
            assertEquals(0, dao.finishResearch(encounter.id, "old-request", "ready", "wrong company", "[]", "", 1))
            assertEquals("new identity", dao.researchFor(encounter.id)!!.identity)
        } finally { database.close() }
    }
}
