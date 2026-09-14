package com.example.handshakecontactcapture.export

import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.net.Uri
import android.provider.ContactsContract as CC
import android.provider.ContactsContract.CommonDataKinds.*
import android.telephony.PhoneNumberUtils
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.ensureActive

data class GoogleExportRow(val contact: Contact, val notes: String, val rawId: Long? = null,
    val version: Long? = null, val warning: String = "", val duplicate: Boolean = false, val blocked: Boolean = false)
data class GoogleExportPlan(val account: String, val rows: List<GoogleExportRow>)
data class ExportOutcome(val name: String, val message: String, val rawId: Long? = null)

/** All provider and Room operations are called from ExportViewModel's IO dispatcher. */
class GoogleContactsExport(private val context: Context, private val dao: HandshakeDao) {
    private val resolver get() = context.contentResolver
    val markerMime = "vnd.android.cursor.item/vnd.${context.packageName}.export"
    private fun marker(id: String) = "${context.packageName}:$id"
    fun verifyAccount(name: String) {
        require(name.isNotBlank()) { "Choose a Google account first." }
        require(AccountManager.get(context).getAccountsByType("com.google").any { it.name == name }) {
            "This Google account is not available to Handshake. Select it again with the account picker, or use CSV."
        }
        require(ContentResolver.getSyncAdapterTypes().any {
            it.accountType == "com.google" && it.authority == CC.AUTHORITY && it.supportsUploading()
        }) { "No writable Google Contacts sync adapter is available. Use CSV on this device." }
    }
    private fun owned(id: String, account: String): List<Long> = resolver.query(CC.Data.CONTENT_URI,
        arrayOf(CC.Data.RAW_CONTACT_ID), "${CC.Data.MIMETYPE}=? AND ${CC.Data.DATA1}=? AND ${CC.RawContacts.ACCOUNT_NAME}=? AND ${CC.RawContacts.ACCOUNT_TYPE}=?",
        arrayOf(markerMime, marker(id), account, "com.google"), null)?.use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    } ?: error("Could not check existing exports.")

    private fun rawVersion(id: Long, account: String): Long? = resolver.query(CC.RawContacts.CONTENT_URI,
        arrayOf(CC.RawContacts.VERSION), "${CC.RawContacts._ID}=? AND ${CC.RawContacts.ACCOUNT_NAME}=? AND ${CC.RawContacts.ACCOUNT_TYPE}=? AND ${CC.RawContacts.DELETED}=0",
        arrayOf(id.toString(), account, "com.google"), null)?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun hasMatch(contact: Contact): Boolean {
        for (email in methods(contact.email)) {
            val found = resolver.query(CC.Data.CONTENT_URI, arrayOf(CC.Data._ID),
                "${CC.Data.MIMETYPE}=? AND ${CC.Data.DATA1}=? COLLATE NOCASE", arrayOf(Email.CONTENT_ITEM_TYPE, email.value), null)
                ?.use { it.moveToFirst() } ?: error("Could not check duplicate email addresses.")
            if (found) return true
        }
        for (phone in methods(contact.phone)) {
            if (PhoneNumberUtils.normalizeNumber(phone.value).count { it.isDigit() } < 7) continue
            val uri = Uri.withAppendedPath(CC.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone.value))
            if (resolver.query(uri, arrayOf(CC.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() }
                ?: error("Could not check duplicate phone numbers.")) return true
        }
        return false
    }
    private fun keys(contact: Contact) = methods(contact.email).map { "email:${it.value.lowercase(java.util.Locale.ROOT)}" } +
        methods(contact.phone).map { PhoneNumberUtils.normalizeNumber(it.value) }.filter { value -> value.count { it.isDigit() } >= 7 }.map { "phone:$it" }

    suspend fun prepare(snapshot: ExportSnapshot, account: String): GoogleExportPlan {
        verifyAccount(account)
        val groups = snapshot.entries.groupBy { it.connection.contact.id }
        val selectedKeys = groups.values.flatMap { keys(it.first().connection.contact).distinct() }.groupingBy { it }.eachCount()
        val rows = groups.values.map { entries ->
            val contact = entries.first().connection.contact
            val notes = exportedNotes(entries, snapshot.options)
            val linked = owned(contact.id, account).distinct()
            val rawId = linked.singleOrNull()
            val version = rawId?.let { rawVersion(it, account) }
            val history = dao.contactExport(contact.id, account)
            val lostLink = rawId == null && history != null && rawVersion(history.rawContactId, account) != null
            val tooLarge = listOf(contact.name, contact.company, contact.title, contact.phone, contact.email, contact.website, contact.address, notes).sumOf { it.length } > 100_000 ||
                (methods(contact.phone).size + methods(contact.email).size + contact.website.lines().size) > 150
            val duplicate = rawId == null && (hasMatch(contact) || keys(contact).any { (selectedKeys[it] ?: 0) > 1 })
            val warning = when {
                linked.size > 1 || lostLink || (rawId != null && version == null) -> "Export link is ambiguous or changed. Review this contact in Google Contacts; use CSV for now."
                tooLarge -> "Too much data for a single Contacts write. Use CSV or shorten this connection."
                duplicate -> "Possible existing contact or duplicate in this selection (matching email/phone). No merge will be performed."
                rawId != null && history != null && version != history.providerVersion -> "This contact changed in Contacts since export. The fields shown here will replace its exported contact fields and notes."
                rawId != null -> "Update the previously exported contact. Its contact fields and notes will be replaced with this preview; photos and other data types remain."
                history != null -> "Previous export is no longer present. Create a replacement contact."
                else -> "Create a new contact in the selected Google account."
            }
            GoogleExportRow(contact, notes, rawId, version, warning, duplicate,
                linked.size > 1 || lostLink || tooLarge || (rawId != null && version == null))
        }
        return GoogleExportPlan(account, rows)
    }

    fun operations(row: GoogleExportRow, account: String): ArrayList<ContentProviderOperation> {
        val ops = arrayListOf<ContentProviderOperation>()
        val managed = listOf(StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE,
            Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE)
        if (row.rawId == null) {
            ops += ContentProviderOperation.newInsert(CC.RawContacts.CONTENT_URI)
                .withValue(CC.RawContacts.ACCOUNT_NAME, account).withValue(CC.RawContacts.ACCOUNT_TYPE, "com.google").build()
        } else {
            ops += ContentProviderOperation.newAssertQuery(CC.RawContacts.CONTENT_URI)
                .withSelection("${CC.RawContacts._ID}=? AND ${CC.RawContacts.ACCOUNT_NAME}=? AND ${CC.RawContacts.ACCOUNT_TYPE}=? AND ${CC.RawContacts.VERSION}=? AND ${CC.RawContacts.DELETED}=0",
                    arrayOf(row.rawId.toString(), account, "com.google", row.version.toString())).withExpectedCount(1).build()
            ops += ContentProviderOperation.newAssertQuery(CC.Data.CONTENT_URI)
                .withSelection("${CC.Data.RAW_CONTACT_ID}=? AND ${CC.Data.MIMETYPE}=? AND ${CC.Data.DATA1}=?",
                    arrayOf(row.rawId.toString(), markerMime, marker(row.contact.id))).withExpectedCount(1).build()
            ops += ContentProviderOperation.newDelete(CC.Data.CONTENT_URI)
                .withSelection("${CC.Data.RAW_CONTACT_ID}=? AND ${CC.Data.MIMETYPE} IN (${managed.joinToString(",") { "?" }})",
                    (listOf(row.rawId.toString()) + managed).toTypedArray()).build()
        }
        fun data(mime: String, values: Map<String, Any>) {
            val builder = ContentProviderOperation.newInsert(CC.Data.CONTENT_URI).withValue(CC.Data.MIMETYPE, mime)
            if (row.rawId == null) builder.withValueBackReference(CC.Data.RAW_CONTACT_ID, 0)
            else builder.withValue(CC.Data.RAW_CONTACT_ID, row.rawId)
            values.forEach { (key, value) -> builder.withValue(key, value) }
            ops += builder.build()
        }
        val c = row.contact
        data(StructuredName.CONTENT_ITEM_TYPE, mapOf(StructuredName.DISPLAY_NAME to c.name.ifBlank { c.company }))
        if (c.company.isNotBlank() || c.title.isNotBlank()) data(Organization.CONTENT_ITEM_TYPE,
            mapOf(Organization.COMPANY to c.company, Organization.TITLE to c.title, Organization.TYPE to Organization.TYPE_WORK))
        methods(c.phone).forEach { data(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to it.value,
            Phone.TYPE to if (it.label.isBlank()) Phone.TYPE_OTHER else Phone.TYPE_CUSTOM, Phone.LABEL to it.label)) }
        methods(c.email).forEach { data(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to it.value,
            Email.TYPE to if (it.label.isBlank()) Email.TYPE_OTHER else Email.TYPE_CUSTOM, Email.LABEL to it.label)) }
        c.website.lines().filter { it.isNotBlank() }.forEach { data(Website.CONTENT_ITEM_TYPE, mapOf(Website.URL to it.trim(), Website.TYPE to Website.TYPE_OTHER)) }
        if (c.address.isNotBlank()) data(StructuredPostal.CONTENT_ITEM_TYPE, mapOf(StructuredPostal.FORMATTED_ADDRESS to c.address, StructuredPostal.TYPE to StructuredPostal.TYPE_OTHER))
        if (row.notes.isNotBlank()) data(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to row.notes))
        if (row.rawId == null) data(markerMime, mapOf(CC.Data.DATA1 to marker(c.id)))
        return ops
    }

    suspend fun export(plan: GoogleExportPlan, allowDuplicates: Set<String>, progress: (ExportOutcome) -> Unit) {
        verifyAccount(plan.account)
        for (row in plan.rows) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val display = row.contact.name.ifBlank { row.contact.company }
            if (row.blocked || (row.duplicate && row.contact.id !in allowDuplicates)) {
                progress(ExportOutcome(display, "Skipped: ${row.warning}")); continue
            }
            val history = ContactExport(row.contact.id, plan.account, row.rawId ?: 0, row.version ?: 0)
            try {
                // Recheck a new export before writing, including recovery after an interrupted earlier run.
                if (row.rawId == null && (owned(row.contact.id, plan.account).isNotEmpty() ||
                    (row.contact.id !in allowDuplicates && hasMatch(row.contact)))) {
                    progress(ExportOutcome(display, "Skipped: Contacts changed after preview. Preview again.")); continue
                }
                dao.saveContactExport(history)
                val result = resolver.applyBatch(CC.AUTHORITY, operations(row, plan.account))
                val id = row.rawId ?: ContentUris.parseId(requireNotNull(result.first().uri))
                val version = rawVersion(id, plan.account) ?: error("Saved account could not be verified")
                dao.saveContactExport(history.copy(rawContactId = id, providerVersion = version, exportedAt = System.currentTimeMillis(), outcome = "saved"))
                progress(ExportOutcome(display, "${if (row.rawId == null) "Created" else "Updated"} in Google account on this device. Cloud sync pending/not verified.", id))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                runCatching { dao.saveContactExport(history.copy(outcome = "needs_review")) }
                progress(ExportOutcome(display, "Could not confirm export. Preview again to check its status; do not create a separate duplicate."))
            }
        }
    }
}
