package com.example.handshakecontactcapture.export

import com.example.handshakecontactcapture.ai.AiContract
import com.example.handshakecontactcapture.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ExportOptions(val research: Boolean = true, val eventContext: Boolean = true,
    val privateNotes: Boolean = false, val transcript: Boolean = false)
data class ExportEntry(val event: Event, val connection: Connection, val research: Research?, val memo: ConversationSummary?)
data class ExportSnapshot(val entries: List<ExportEntry>, val options: ExportOptions)
data class ContactValue(val value: String, val label: String = "")
fun methods(value: String): List<ContactValue> = value.lines().filter { it.isNotBlank() }.map {
    val parts = it.trim().split(Regex(":\\s+"), limit = 2)
    if (parts.size == 2 && parts[0].length <= 30 && !parts[0].contains('/') && parts[1].isNotBlank()) ContactValue(parts[1], parts[0])
    else ContactValue(it.trim())
}
fun isoTime(value: Long): String = if (value <= 0) "" else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(value))

fun exportedNotes(entries: List<ExportEntry>, options: ExportOptions): String = entries.mapNotNull { entry ->
    val parts = mutableListOf<String>()
    if (options.eventContext) parts += "Event: ${entry.event.name}\n${entry.event.location}\n${entry.event.dates}\nMet: ${isoTime(entry.connection.encounter.createdAt)}"
    if (options.research) {
        entry.research?.takeIf { it.summary.isNotBlank() }?.let {
            parts += "AI company research (${isoTime(it.researchedAt)}; check identity/sources):\n${it.summary}\n" +
                AiContract.citations(it.citations).map { source -> source.url }.distinct().joinToString("\n")
        }
        entry.connection.encounter.printedClaims.takeIf { it.isNotBlank() }?.let { parts += "Printed claims (unverified):\n$it" }
    }
    if (options.privateNotes) {
        entry.connection.encounter.notes.takeIf { it.isNotBlank() }?.let { parts += "Private meeting notes:\n$it" }
        entry.connection.encounter.followUp.takeIf { it.isNotBlank() }?.let {
            parts += "Follow-up (${if (entry.connection.encounter.done) "done" else "open"}):\n$it"
        }
    }
    if (options.transcript) entry.memo?.transcript?.takeIf { it.isNotBlank() }?.let { parts += "Private conversation summary:\n$it" }
    parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}.distinct().joinToString("\n\n---\n\n")

object ArchiveCsv {
    val headers = ("schema_version,event_id,event_name,event_start_date,event_end_date,encounter_id,captured_at,company_id,company_name,company_website,contact_id,full_name,job_title,phones_json,emails_json,addresses_json,websites_json,company_summary,capabilities_json,research_sources_json,researched_at,personal_notes,memo_transcript,followups_json,review_status,event_location,event_dates_text,printed_claims,research_identity").split(',')
    /** Quote every cell. Prefix text with an apostrophe to preserve dangerous formulas/leading zeros. */
    fun cell(value: String): String {
        val trimmed = value.dropWhile { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' || it == '\u200B' }
        val protect = trimmed.firstOrNull() in listOf('=', '+', '-', '@') || trimmed.matches(Regex("0[0-9]+"))
        val safe = if (protect) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun values(entry: ExportEntry, options: ExportOptions): List<String> {
        val contact = entry.connection.contact
        val encounter = entry.connection.encounter
        val research = entry.research.takeIf { options.research }
        fun list(value: String) = JSONArray(value.lines().filter { it.isNotBlank() }).toString()
        val followups = JSONArray()
        if (options.privateNotes && encounter.followUp.isNotBlank()) followups.put(JSONObject()
            .put("action", encounter.followUp).put("status", if (encounter.done) "done" else "open")
            .put("due_date", JSONObject.NULL).put("recipient", JSONObject.NULL))
        return listOf("1", entry.event.id, entry.event.name, "", "", encounter.id, isoTime(encounter.createdAt), "",
            contact.company, contact.website, if (contact.name.isBlank()) "" else contact.id, contact.name, contact.title,
            list(contact.phone), list(contact.email), JSONArray(listOfNotNull(contact.address.takeIf { it.isNotBlank() })).toString(), list(contact.website),
            research?.summary.orEmpty(), "[]", research?.citations ?: "[]", isoTime(research?.researchedAt ?: 0),
            if (options.privateNotes) encounter.notes else "", if (options.transcript) entry.memo?.transcript.orEmpty() else "",
            followups.toString(), "saved", entry.event.location, entry.event.dates,
            if (options.research) encounter.printedClaims else "", research?.identity.orEmpty())
    }
    fun write(snapshot: ExportSnapshot, writer: Writer) {
        writer.write(headers.joinToString(",", transform = ::cell) + "\r\n")
        snapshot.entries.forEach { writer.write(values(it, snapshot.options).joinToString(",", transform = ::cell) + "\r\n") }
    }
}
