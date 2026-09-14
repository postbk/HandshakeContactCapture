package com.example.handshakecontactcapture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.data.HandshakeDatabase
import com.example.handshakecontactcapture.data.Event
import com.example.handshakecontactcapture.data.Capture
import com.example.handshakecontactcapture.capture.PhotoStorage
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import org.json.JSONArray
import java.io.File
import org.junit.Assert.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun exportPreviewPreservesSelectionAndPrivacyAcrossRecreation() {
        val dao = HandshakeDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext).dao()
        val event = Event(name = "Export UI ${System.currentTimeMillis()}")
        val person = com.example.handshakecontactcapture.data.Contact(name = "Export Fixture")
        val encounter = com.example.handshakecontactcapture.data.Encounter(eventId = event.id, contactId = person.id,
            notes = "Private export fixture")
        try {
            runBlocking { dao.saveEvent(event); dao.saveConnection(person, encounter) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(event.name).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText(event.name).performClick()
            compose.onNodeWithText("Export event").performScrollTo().performClick()
            compose.onNodeWithText("1 selected").assertExists()
            compose.onNodeWithText("Preview CSV export").performScrollTo().performClick()
            compose.onNodeWithText("Save CSV file").performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Save CSV file").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Show exported fields").performScrollTo().performClick()
            compose.onNodeWithText("personal_notes: ").performScrollTo().assertExists()
            compose.onAllNodesWithText("Private export fixture", substring = true).assertCountEquals(0)
        } finally { runBlocking { dao.deleteEvent(event.id); dao.deleteUnusedContacts() } }
    }

    @Test fun summaryDraftSurvivesRecreationAndUploadRequiresReview() {
        val dao = HandshakeDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext).dao()
        val event = Event(name = "Summary UI ${System.currentTimeMillis()}")
        val person = com.example.handshakecontactcapture.data.Contact(name = "Summary Fixture")
        val encounter = com.example.handshakecontactcapture.data.Encounter(eventId = event.id, contactId = person.id)
        try {
            runBlocking { dao.saveEvent(event); dao.saveConnection(person, encounter) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(event.name).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText(event.name).performClick()
            compose.onNodeWithText(person.name).performScrollTo().performClick()
            compose.onNodeWithText("Conversation summary").performScrollTo().performClick()
            compose.onNodeWithText("Editable conversation summary").performScrollTo().performTextInput("Send specifications next week.")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Editable conversation summary").performScrollTo().assertTextContains("Send specifications next week.")
            compose.onNodeWithText("Save summary locally").performScrollTo().performClick()
            compose.waitUntil(10_000) { runBlocking { dao.summary(encounter.id)?.transcript == "Send specifications next week." } }
            compose.onNodeWithText("Analyze summary").performScrollTo().performClick()
            compose.onNodeWithText("Send summary to OpenAI?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            runBlocking { assertEquals("draft", dao.summary(encounter.id)!!.state) }
        } finally { runBlocking { dao.deleteEvent(event.id); dao.deleteUnusedContacts() } }
    }

    @Test fun extractedContactCanBeCorrectedAndResearchRequiresConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = HandshakeDatabase.get(context).dao()
        val event = Event(name = "UI photo test ${System.currentTimeMillis()}")
        val photo = File(context.cacheDir, "ui-photo-test.jpg")
        val storage = PhotoStorage(context)
        var filename: String? = null
        try {
            val bitmap = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLACK; textSize = 40f }
                drawText("Example Robotics", 40f, 100f, paint)
                drawText("Alex Example", 40f, 180f, paint)
                drawText("alex@example.test", 40f, 260f, paint)
            }
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            filename = storage.import(Uri.fromFile(photo))
            val capture = Capture(eventId = event.id, images = JSONArray(listOf(filename)).toString(),
                state = "review", result = AiFixtures.extraction(), model = "fixture")
            runBlocking { dao.saveEvent(event); dao.insertCapture(capture) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(event.name).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText(event.name).performClick()
            compose.onNodeWithText("Photo batch", substring = true).performClick()
            compose.onAllNodesWithText("Review & save")[0].performScrollTo().performClick()
            compose.onNodeWithText("Full name").performScrollTo().performTextClearance()
            compose.onNodeWithText("Full name").performTextInput("Alex Corrected")
            compose.onNodeWithText("Save connection").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Saved to event").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            runBlocking { assertEquals("Alex Corrected", dao.connection("${capture.id}-0")!!.contact.name) }
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText("Alex Corrected").performScrollTo().performClick()
            compose.onNodeWithText("Research company").performScrollTo().performClick()
            compose.onNodeWithText("Confirm the company").assertIsDisplayed()
            compose.onNodeWithText("Confirm & research").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            runBlocking { dao.deleteEvent(event.id); dao.deleteUnusedContacts() }
            photo.delete()
            filename?.let { storage.delete(it) }
        }
    }

    @Test fun createEventAndSaveCompanyConnection() {
        val eventName = "UI test ${System.currentTimeMillis()}"
        val dao = HandshakeDatabase.get(InstrumentationRegistry.getInstrumentation().targetContext).dao()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("+ New event").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("+ New event").performClick()
            compose.onNodeWithText("Event name *").performTextInput(eventName)
            compose.onNodeWithText("Create event").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("+ Add connection").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("+ Add connection").performClick()
            compose.onNodeWithText("Company").performTextInput("Synthetic Test Company")
            compose.onNodeWithText("Save connection").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic Test Company").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("Synthetic Test Company").assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Synthetic Test Company").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("Synthetic Test Company").assertIsDisplayed()
        } finally {
            runBlocking {
                dao.events().first().filter { it.name == eventName }.forEach { dao.deleteEvent(it.id) }
                dao.deleteUnusedContacts()
            }
        }
    }
}
