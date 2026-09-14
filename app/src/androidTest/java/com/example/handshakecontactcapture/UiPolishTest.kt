package com.example.handshakecontactcapture

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.data.*
import com.example.handshakecontactcapture.ui.*
import com.example.handshakecontactcapture.ui.theme.HandshakeContactCaptureTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class UiPolishTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val event = Event(name = "Design & Manufacturing Expo", location = "Chicago", dates = "September 13–15, 2026")
    private val person = Contact(name = "Alex Morgan", company = "Northstar Robotics", title = "Product Director")
    private val connection = Connection(Encounter(eventId = event.id, contactId = person.id,
        notes = "Exploring lightweight assembly systems for their next production line.", followUp = "Send the product brief on Friday"), person)

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun lightOverviewShowsEventAndCounts() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            HandshakeContactCaptureTheme(darkTheme = false) {
                Surface { Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { EventsOverview(listOf(event), listOf(connection)) {} } }
            }
        } }
        compose.onNodeWithText(event.name).performScrollTo().assertIsDisplayed()
        screenshot("ui-overview-light.png")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.getDrawable(R.mipmap.handshake_launcher)!!
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 192, 192)
        icon.draw(android.graphics.Canvas(bitmap))
        File(context.getExternalFilesDir(null), "launcher-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun narrowDarkLargeTextKeepsActionsAndFollowUpReachable() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                HandshakeContactCaptureTheme(darkTheme = true) {
                    Surface(Modifier.width(360.dp).fillMaxHeight()) {
                        EventNotebook(event, listOf(connection), emptyList(), false, "", {}, false, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        } }
        compose.onNodeWithText("Scan card / fact sheet").performScrollTo().assertIsDisplayed()
        screenshot("ui-event-dark-large-text.png")
        compose.onNodeWithText(person.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Next: Send the product brief on Friday").performScrollTo().assertIsDisplayed()
    }
}
