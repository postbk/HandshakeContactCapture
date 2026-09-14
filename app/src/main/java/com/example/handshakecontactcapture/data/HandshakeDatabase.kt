package com.example.handshakecontactcapture.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Entity(tableName = "events")
data class Event(@PrimaryKey val id: String = newId(), val name: String,
    val location: String = "", val dates: String = "", val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "contacts")
data class Contact(@PrimaryKey val id: String = newId(), val name: String = "",
    val company: String = "", val title: String = "", val email: String = "",
    val phone: String = "", val website: String = "", val address: String = "")

@Entity(tableName = "encounters", foreignKeys = [
    ForeignKey(entity = Event::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Contact::class, parentColumns = ["id"], childColumns = ["contactId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("eventId"), Index("contactId")])
data class Encounter(@PrimaryKey val id: String = newId(), val eventId: String, val contactId: String,
    val notes: String = "", val followUp: String = "", val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val captureId: String = "",
    @ColumnInfo(defaultValue = "''") val printedClaims: String = "")

@Entity(tableName = "captures", foreignKeys = [ForeignKey(entity = Event::class,
    parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)], indices = [Index("eventId")])
data class Capture(@PrimaryKey val id: String = newId(), val eventId: String,
    val images: String = "[]", val state: String = "draft", val result: String = "",
    val error: String = "", val model: String = "", val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "research", foreignKeys = [ForeignKey(entity = Encounter::class,
    parentColumns = ["id"], childColumns = ["encounterId"], onDelete = ForeignKey.CASCADE)])
data class Research(@PrimaryKey val encounterId: String, val identity: String,
    val state: String = "queued", val summary: String = "", val citations: String = "[]",
    val error: String = "", val model: String = "", val researchedAt: Long = 0,
    val requestId: String = newId())

data class Connection(@Embedded val encounter: Encounter,
    @Relation(parentColumn = "contactId", entityColumn = "id") val contact: Contact)

@Entity(tableName = "conversation_summaries", foreignKeys = [ForeignKey(entity = Encounter::class,
    parentColumns = ["id"], childColumns = ["encounterId"], onDelete = ForeignKey.CASCADE)])
data class ConversationSummary(@PrimaryKey val encounterId: String, val transcript: String = "",
    val originalTranscript: String = "", val state: String = "draft", val requestId: String = newId(),
    val context: String = "", val model: String = "", val result: String = "", val error: String = "",
    val applied: String = "[]", val analyzedAt: Long = 0, val audioPath: String = "")

@Entity(tableName = "contact_exports", primaryKeys = ["contactId", "accountName"])
data class ContactExport(val contactId: String, val accountName: String, val rawContactId: Long = 0,
    val providerVersion: Long = 0, val exportedAt: Long = 0, val outcome: String = "pending")

@Dao
interface HandshakeDao {
    @Query("SELECT * FROM contact_exports WHERE contactId = :id AND accountName = :account") suspend fun contactExport(id: String, account: String): ContactExport?
    @Upsert suspend fun saveContactExport(record: ContactExport)
    @Transaction suspend fun finishTranscription(id: String, request: String, text: String) {
        val record = summary(id) ?: return
        if (record.requestId != request || record.state !in listOf("transcription_queued", "transcription_running")) return
        require(text.isNotBlank() && text.length <= 8000)
        val merged = listOf(record.transcript, text).filter { it.isNotBlank() }.joinToString("\n")
        if (merged.length > 8000) throw com.example.handshakecontactcapture.ai.AiFailure("Shorten the existing summary before retrying transcription. Your audio is saved.")
        saveSummary(record.copy(transcript = merged, originalTranscript = text, state = "draft", error = ""))
    }
    @Query("SELECT * FROM conversation_summaries") fun summaries(): Flow<List<ConversationSummary>>
    @Query("SELECT * FROM conversation_summaries WHERE encounterId = :id") suspend fun summary(id: String): ConversationSummary?
    @Upsert suspend fun saveSummary(summary: ConversationSummary)
    @Query("DELETE FROM conversation_summaries WHERE encounterId = :id") suspend fun deleteSummary(id: String)
    @Query("UPDATE conversation_summaries SET state = :state, result = :result, error = :error, analyzedAt = :time WHERE encounterId = :id AND requestId = :requestId")
    suspend fun finishSummary(id: String, requestId: String, state: String, result: String, error: String, time: Long): Int
    @Transaction suspend fun applySummary(id: String, requestId: String, index: Int, text: String, followUp: Boolean) {
        val record = summary(id) ?: return
        require(record.requestId == requestId && record.state == "review")
        val items = com.example.handshakecontactcapture.ai.SummaryContract.parse(record.result, record.transcript, record.context)
        require(index in items.indices && text.isNotBlank() && text.length <= 4000)
        val applied = org.json.JSONArray(record.applied)
        if ((0 until applied.length()).any { applied.getInt(it) == index }) return
        val meeting = connection(id)?.encounter ?: return
        fun append(old: String) = listOf(old, text.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
        // Read the current encounter in this transaction so intervening edits are preserved.
        saveEncounter(if (followUp) meeting.copy(followUp = append(meeting.followUp), done = false)
            else meeting.copy(notes = append(meeting.notes)))
        saveSummary(record.copy(applied = applied.put(index).toString()))
    }
    @Query("SELECT * FROM captures ORDER BY createdAt DESC") fun captures(): Flow<List<Capture>>
    @Query("SELECT * FROM research") fun research(): Flow<List<Research>>
    @Query("SELECT * FROM captures WHERE id = :id") suspend fun capture(id: String): Capture?
    @Query("SELECT * FROM research WHERE encounterId = :id") suspend fun researchFor(id: String): Research?
    @Transaction @Query("SELECT * FROM encounters WHERE id = :id") suspend fun connection(id: String): Connection?
    @Insert suspend fun insertCapture(capture: Capture)
    @Update suspend fun updateCapture(capture: Capture): Int
    @Upsert suspend fun saveResearch(research: Research)
    @Query("UPDATE research SET state = :state, summary = :summary, citations = :citations, error = :error, researchedAt = :time WHERE encounterId = :id AND requestId = :requestId")
    suspend fun finishResearch(id: String, requestId: String, state: String, summary: String, citations: String, error: String, time: Long): Int
    @Query("DELETE FROM captures WHERE id = :id") suspend fun deleteCapture(id: String)
    @Transaction suspend fun acceptCandidate(captureId: String, contact: Contact, encounter: Encounter) {
        require(capture(captureId)?.state == "review") { "Capture is no longer available for review." }
        // Deterministic IDs from capture + candidate index make repeated Save taps idempotent.
        if (connection(encounter.id) == null) saveConnection(contact, encounter)
    }
    @Query("SELECT * FROM events ORDER BY createdAt DESC") fun events(): Flow<List<Event>>
    @Transaction @Query("SELECT * FROM encounters ORDER BY createdAt DESC")
    fun connections(): Flow<List<Connection>>
    @Upsert suspend fun saveEvent(event: Event)
    @Upsert suspend fun saveContact(contact: Contact)
    @Upsert suspend fun saveEncounter(encounter: Encounter)
    @Transaction suspend fun saveConnection(contact: Contact, encounter: Encounter) {
        require(contact.name.isNotBlank() || contact.company.isNotBlank())
        saveContact(contact)
        saveEncounter(encounter)
    }
    @Query("DELETE FROM encounters WHERE id = :id") suspend fun deleteEncounter(id: String)
    @Query("DELETE FROM events WHERE id = :id") suspend fun deleteEvent(id: String)
    @Query("DELETE FROM contacts WHERE id NOT IN (SELECT contactId FROM encounters)") suspend fun deleteUnusedContacts()
    @Transaction suspend fun deleteConnection(id: String) {
        deleteEncounter(id)
        deleteUnusedContacts()
    }
}

@Database(entities = [Event::class, Contact::class, Encounter::class, Capture::class, Research::class, ConversationSummary::class, ContactExport::class], version = 4, exportSchema = true)
abstract class HandshakeDatabase : RoomDatabase() {
    abstract fun dao(): HandshakeDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS contact_exports (contactId TEXT NOT NULL, accountName TEXT NOT NULL, rawContactId INTEGER NOT NULL, providerVersion INTEGER NOT NULL, exportedAt INTEGER NOT NULL, outcome TEXT NOT NULL, PRIMARY KEY(contactId, accountName))")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS conversation_summaries (encounterId TEXT NOT NULL PRIMARY KEY, transcript TEXT NOT NULL, originalTranscript TEXT NOT NULL, state TEXT NOT NULL, requestId TEXT NOT NULL, context TEXT NOT NULL, model TEXT NOT NULL, result TEXT NOT NULL, error TEXT NOT NULL, applied TEXT NOT NULL, analyzedAt INTEGER NOT NULL, audioPath TEXT NOT NULL, FOREIGN KEY(encounterId) REFERENCES encounters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE encounters ADD COLUMN captureId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE encounters ADD COLUMN printedClaims TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS captures (id TEXT NOT NULL PRIMARY KEY, eventId TEXT NOT NULL, images TEXT NOT NULL, state TEXT NOT NULL, result TEXT NOT NULL, error TEXT NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(eventId) REFERENCES events(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_captures_eventId ON captures(eventId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS research (encounterId TEXT NOT NULL PRIMARY KEY, identity TEXT NOT NULL, state TEXT NOT NULL, summary TEXT NOT NULL, citations TEXT NOT NULL, error TEXT NOT NULL, model TEXT NOT NULL, researchedAt INTEGER NOT NULL, requestId TEXT NOT NULL, FOREIGN KEY(encounterId) REFERENCES encounters(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        @Volatile private var instance: HandshakeDatabase? = null
        fun get(context: Context): HandshakeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                HandshakeDatabase::class.java, "handshake.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
