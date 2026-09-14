package com.example.handshakecontactcapture

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.handshakecontactcapture.ai.objects
import com.example.handshakecontactcapture.data.HandshakeDatabase
import com.example.handshakecontactcapture.data.newId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MigrationTest {
    @Test fun versionOneContactsSurvivePhotoResearchMigration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${newId()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = instrumentation.context.assets.open(
            "com.example.handshakecontactcapture.data.HandshakeDatabase/1.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
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
                old.execSQL("INSERT INTO events VALUES ('event', 'Saved event', '', '', 1)")
                old.execSQL("INSERT INTO contacts VALUES ('person', 'Saved Person', 'Example', '', '', '', '', '')")
                old.execSQL("INSERT INTO encounters VALUES ('meeting', 'event', 'person', 'Private note', 'Send specs', 0, 1)")
                old.version = 1
            }
            // Room itself migrates and validates every table on first open.
            val upgraded = Room.databaseBuilder(context, HandshakeDatabase::class.java, name)
                .addMigrations(HandshakeDatabase.MIGRATION_1_2, HandshakeDatabase.MIGRATION_2_3, HandshakeDatabase.MIGRATION_3_4).build()
            try {
                val meeting = upgraded.dao().connections().first().single()
                assertEquals("Saved Person", meeting.contact.name)
                assertEquals("Private note", meeting.encounter.notes)
                assertEquals("", meeting.encounter.captureId)
                assertEquals("", meeting.encounter.printedClaims)
                assertTrue(upgraded.dao().captures().first().isEmpty())
                assertTrue(upgraded.dao().research().first().isEmpty())
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }
}
