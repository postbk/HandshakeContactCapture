package com.example.handshakecontactcapture

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.ContactsContract as CC
import android.provider.ContactsContract.CommonDataKinds.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.ai.objects
import com.example.handshakecontactcapture.data.*
import com.example.handshakecontactcapture.data.Event
import com.example.handshakecontactcapture.export.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample(): ExportEntry {
        val event = Event(name = "Expo, \"2026\"", dates = "September 13–14", location = "Montréal")
        val contact = Contact(name = "  =DANGEROUS()", company = "Example", phone = "Office: 00123\nMobile: +1 202 555 0100", email = "a@example.test\nb@example.test", address = "1 Main St\nMontréal")
        val encounter = Encounter(eventId = event.id, contactId = contact.id, notes = "PRIVATE\n\"meeting\"", followUp = "PRIVATE task")
        return ExportEntry(event, Connection(encounter, contact), null, ConversationSummary(encounter.id, "PRIVATE transcript"))
    }
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i++]
            when {
                ch == '"' && quoted && i < text.length && text[i] == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { row += cell.toString(); cell.clear() }
                ch == '\r' && !quoted -> { require(text[i++] == '\n'); row += cell.toString(); rows += row; row = mutableListOf(); cell.clear() }
                else -> cell.append(ch)
            }
        }
        require(!quoted && row.isEmpty() && cell.isEmpty())
        return rows
    }
    @Test fun csvPreservesUnicodeListsQuotingAndPrivacyWithFormulaProtection() {
        val entry = sample()
        fun archive(options: ExportOptions): List<List<String>> = StringWriter().let {
            ArchiveCsv.write(ExportSnapshot(listOf(entry), options), it); parseCsv(it.toString())
        }
        val rows = archive(ExportOptions())
        assertEquals(ArchiveCsv.headers, rows.first())
        val values = rows.first().zip(rows[1]).toMap()
        assertEquals("Expo, \"2026\"", values["event_name"])
        assertEquals("Montréal", values["event_location"])
        assertEquals("September 13–14", values["event_dates_text"])
        assertEquals("", values["event_start_date"])
        assertEquals("'  =DANGEROUS()", values["full_name"])
        assertEquals(2, JSONArray(values["phones_json"]).length())
        assertEquals(2, JSONArray(values["emails_json"]).length())
        assertEquals("1 Main St\nMontréal", JSONArray(values["addresses_json"]).getString(0))
        assertFalse(rows[1].joinToString().contains("PRIVATE"))
        val privateRows = archive(ExportOptions(privateNotes = true, transcript = true))
        assertTrue(privateRows[1].contains("PRIVATE\n\"meeting\""))
        assertTrue(privateRows[1].contains("PRIVATE transcript"))
        listOf("=1", "\t +1", "\uFEFF@SUM(1)", "\n-1", "00123").forEach { assertTrue(ArchiveCsv.cell(it).startsWith("\"'")) }
        val company = entry.copy(connection = entry.connection.copy(contact = entry.connection.contact.copy(name = "")))
        assertEquals("", ArchiveCsv.values(company, ExportOptions())[ArchiveCsv.headers.indexOf("contact_id")])
        assertFalse(exportedNotes(listOf(entry), ExportOptions()).contains("PRIVATE"))
    }

    private class CaptureProvider : ContentProvider() {
        var inserted: ContentValues? = null
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri { inserted = values; return ContentUris.withAppendedId(uri, 42) }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(arrayOf("_id"))
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    @Test fun contactsBatchTargetsGoogleAndLocalFixturePreservesRepeatedFieldsAndUpdates() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        require(context.packageName.endsWith(".verification"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.READ_CONTACTS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.WRITE_CONTACTS)
        val db = Room.inMemoryDatabaseBuilder(context, HandshakeDatabase::class.java).build()
        val adapter = GoogleContactsExport(context, db.dao())
        val entry = sample()
        val row = GoogleExportRow(entry.connection.contact.copy(name = "Synthetic Export ${newId()}"), "")
        val account = "synthetic@example.test"
        val ops = adapter.operations(row, account)
        val capture = CaptureProvider()
        ops.first().apply(capture, emptyArray(), 0)
        assertEquals("com.google", capture.inserted!!.getAsString(CC.RawContacts.ACCOUNT_TYPE))
        assertEquals(account, capture.inserted!!.getAsString(CC.RawContacts.ACCOUNT_NAME))
        // Execute the same data operations against a disposable device-only raw contact.
        // No test writes to the user's Google account.
        ops[0] = ContentProviderOperation.newInsert(CC.RawContacts.CONTENT_URI)
            .withValue(CC.RawContacts.ACCOUNT_NAME, null).withValue(CC.RawContacts.ACCOUNT_TYPE, null).build()
        var rawId: Long? = null
        try {
            rawId = ContentUris.parseId(context.contentResolver.applyBatch(CC.AUTHORITY, ops).first().uri!!)
            fun count(mime: String): Int = context.contentResolver.query(CC.Data.CONTENT_URI, arrayOf(CC.Data._ID),
                "${CC.Data.RAW_CONTACT_ID}=? AND ${CC.Data.MIMETYPE}=?", arrayOf(rawId.toString(), mime), null)!!.use { it.count }
            assertEquals(2, count(Phone.CONTENT_ITEM_TYPE))
            assertEquals(2, count(Email.CONTENT_ITEM_TYPE))
            assertEquals(0, count(Note.CONTENT_ITEM_TYPE))
            assertEquals(1, count(adapter.markerMime))
            val version = context.contentResolver.query(ContentUris.withAppendedId(CC.RawContacts.CONTENT_URI, rawId),
                arrayOf(CC.RawContacts.VERSION), null, null, null)!!.use { it.moveToFirst(); it.getLong(0) }
            val update = adapter.operations(row.copy(rawId = rawId, version = version, contact = row.contact.copy(phone = "999")), account)
            // Replace only the Google-account guard with the matching local-fixture guard.
            update[0] = ContentProviderOperation.newAssertQuery(CC.RawContacts.CONTENT_URI)
                .withSelection("${CC.RawContacts._ID}=? AND ${CC.RawContacts.VERSION}=? AND ${CC.RawContacts.ACCOUNT_NAME} IS NULL",
                    arrayOf(rawId.toString(), version.toString())).withExpectedCount(1).build()
            context.contentResolver.applyBatch(CC.AUTHORITY, update)
            assertEquals(1, count(Phone.CONTENT_ITEM_TYPE))
            assertEquals(2, count(Email.CONTENT_ITEM_TYPE))
            assertEquals(1, count(adapter.markerMime))
            try { context.contentResolver.applyBatch(CC.AUTHORITY, update); fail("Stale provider version accepted") }
            catch (_: OperationApplicationException) {}
            assertEquals(1, count(Phone.CONTENT_ITEM_TYPE))
        } finally {
            rawId?.let { id ->
                val markerExists = context.contentResolver.query(CC.Data.CONTENT_URI, arrayOf(CC.Data._ID),
                    "${CC.Data.RAW_CONTACT_ID}=? AND ${CC.Data.MIMETYPE}=? AND ${CC.Data.DATA1}=?",
                    arrayOf(id.toString(), adapter.markerMime, "${context.packageName}:${row.contact.id}"), null)!!.use { it.moveToFirst() }
                if (markerExists) context.contentResolver.delete(CC.RawContacts.CONTENT_URI,
                    "${CC.RawContacts._ID}=? AND ${CC.RawContacts.ACCOUNT_NAME} IS NULL", arrayOf(id.toString()))
            }
            db.close()
        }
    }

    @Test fun versionThreeMemosSurviveExportHistoryMigration() = runBlocking {
        val name = "export-migration-${newId()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.example.handshakecontactcapture.data.HandshakeDatabase/3.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                schema.getJSONArray("entities").objects().forEach { entity ->
                    old.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                    entity.optJSONArray("indices")?.objects()?.forEach { old.execSQL(it.getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName"))) }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO events VALUES ('event','Fixture','','',1)")
                old.execSQL("INSERT INTO contacts VALUES ('person','Alex','Example','','','','','')")
                old.execSQL("INSERT INTO encounters VALUES ('meeting','event','person','Keep note','Keep task',1,1,'','')")
                old.execSQL("INSERT INTO conversation_summaries VALUES ('meeting','Keep transcript','Original','draft','request','','','','','[]',0,'memo.pcm')")
                old.version = 3
            }
            val db = Room.databaseBuilder(context, HandshakeDatabase::class.java, name).addMigrations(HandshakeDatabase.MIGRATION_3_4).build()
            try {
                assertEquals("Keep transcript", db.dao().summary("meeting")!!.transcript)
                assertEquals("memo.pcm", db.dao().summary("meeting")!!.audioPath)
                db.dao().saveContactExport(ContactExport("person", "fixture@example.test", 123, 2, 1, "saved"))
                assertEquals(123L, db.dao().contactExport("person", "fixture@example.test")!!.rawContactId)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
