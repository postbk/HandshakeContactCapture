package com.example.handshakecontactcapture

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DatabaseTest {
    @Test fun connectionsSurviveReopenAndKeepEventHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "test-${newId()}.db"
        var database = Room.databaseBuilder(context, HandshakeDatabase::class.java, databaseName).build()
        try {
            val first = Event(name = "Synthetic event A")
            val second = Event(name = "Synthetic event B")
            database.dao().saveEvent(first)
            database.dao().saveEvent(second)
            val person = Contact(name = "Synthetic Person", company = "Example Company")
            repeat(100) { index ->
                val contact = if (index == 0) person else Contact(name = "Person $index", company = "Example Company")
                database.dao().saveConnection(contact, Encounter(eventId = first.id, contactId = contact.id, notes = "Meeting $index"))
            }
            val repeated = Encounter(eventId = second.id, contactId = person.id, followUp = "Introduce to test team")
            database.dao().saveConnection(person, repeated)
            val companyOnly = Contact(company = "Company without a named POC")
            database.dao().saveConnection(companyOnly, Encounter(eventId = second.id, contactId = companyOnly.id))
            database.close()
            database = Room.databaseBuilder(context, HandshakeDatabase::class.java, databaseName).build()
            val rows = database.dao().connections().first()
            assertEquals(102, rows.size)
            assertEquals(100, rows.count { it.encounter.eventId == first.id })
            assertEquals(2, rows.count { it.contact.id == person.id })
            assertTrue(rows.any { it.contact.name.isBlank() && it.contact.company == companyOnly.company })
            database.dao().saveEncounter(repeated.copy(done = true))
            assertTrue(database.dao().connections().first().first { it.encounter.id == repeated.id }.encounter.done)
            database.dao().deleteConnection(repeated.id)
            assertEquals(1, database.dao().connections().first().count { it.contact.id == person.id })
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
