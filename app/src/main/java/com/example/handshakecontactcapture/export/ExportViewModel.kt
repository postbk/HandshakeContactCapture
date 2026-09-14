package com.example.handshakecontactcapture.export

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.handshakecontactcapture.data.HandshakeDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class ExportState(val snapshot: ExportSnapshot? = null, val destination: String = "",
    val plan: GoogleExportPlan? = null, val outcomes: List<ExportOutcome> = emptyList(),
    val busy: Boolean = false, val message: String = "")

class ExportViewModel(application: Application) : AndroidViewModel(application) {
    val state = MutableStateFlow(ExportState())
    private val google = GoogleContactsExport(application, HandshakeDatabase.get(application).dao())
    fun reset() { if (!state.value.busy) state.value = ExportState() }
    fun preview(snapshot: ExportSnapshot, destination: String) {
        if (!state.value.busy) state.value = ExportState(snapshot, destination)
    }
    fun report(message: String) { state.value = state.value.copy(message = message) }
    private fun run(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(if (error is IllegalArgumentException) error.message.orEmpty() else "Export could not finish. Your local records are safe. Check permissions/destination and preview again.") }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
    fun prepareGoogle(account: String) {
        val snapshot = state.value.snapshot ?: return report("Select records and preview again.")
        run { state.value = state.value.copy(plan = google.prepare(snapshot, account)) }
    }
    fun saveCsv(uri: Uri) {
        val snapshot = state.value.snapshot ?: return report("The preview expired. Select records and choose a file again.")
        run {
            val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: error("Cannot open destination")
            output.bufferedWriter(Charsets.UTF_8).use { ArchiveCsv.write(snapshot, it) }
            report("Saved ${snapshot.entries.size} connection rows as UTF-8 CSV. Photos/audio are not included.")
        }
    }
    fun exportGoogle(duplicates: Set<String>) {
        val plan = state.value.plan ?: return
        run {
            state.value = state.value.copy(outcomes = emptyList())
            google.export(plan, duplicates) { outcome ->
                state.value = state.value.copy(outcomes = state.value.outcomes + outcome)
            }
            // Require a fresh provider preview before any further writes.
            state.value = state.value.copy(plan = null, message = "Export finished. Review each result below. Google cloud synchronization is handled by your device and is not verified here.")
        }
    }
}
