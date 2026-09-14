package com.example.handshakecontactcapture.ui

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.handshakecontactcapture.data.*
import com.example.handshakecontactcapture.export.*

@Composable
fun ExportScreen(event: Event, connections: List<Connection>, research: List<Research>, memos: List<ConversationSummary>,
    initialEncounterId: String?, model: ExportViewModel = viewModel(key = rememberSaveable { "export-${newId()}" })) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable(event.id, initialEncounterId) {
        mutableStateOf(ArrayList(connections.filter { initialEncounterId == null || it.encounter.id == initialEncounterId }.map { it.encounter.id }))
    }
    var includeResearch by rememberSaveable { mutableStateOf(true) }
    var includeEvent by rememberSaveable { mutableStateOf(true) }
    var includeNotes by rememberSaveable { mutableStateOf(false) }
    var includeTranscript by rememberSaveable { mutableStateOf(false) }
    var allowDuplicates by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var confirmGoogle by rememberSaveable { mutableStateOf(false) }
    fun snapshot() = ExportSnapshot(connections.filter { it.encounter.id in selected }.map {
        ExportEntry(event, it, research.find { r -> r.encounterId == it.encounter.id }, memos.find { m -> m.encounterId == it.encounter.id })
    }, ExportOptions(includeResearch, includeEvent, includeNotes, includeTranscript))
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) model.saveCsv(uri)
    }
    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            if (name != null && type == "com.google") model.prepareGoogle(name)
            else model.report("Choose a Google account. CSV remains available.")
        }
    }
    fun chooseAccount() {
        try { accountPicker.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"),
            "Choose the Google account for this contact export", null, null, null)) }
        catch (_: Exception) { model.report("Google account selection is unavailable. Add a Google account in device settings or use CSV.") }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it } && granted.isNotEmpty()) chooseAccount()
        else model.report("Contacts permission was denied. CSV export still works.")
    }
    fun change() { model.reset(); allowDuplicates = arrayListOf() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenHeading("Export connections", event.name)
            NotebookCard("Share the details that matter", "Choose connections, decide what to include, then review before exporting.")
        }
        item {
            SectionHeading("1. Choose connections")
            Row {
                TextButton(enabled = !state.busy, onClick = { selected = ArrayList(connections.map { it.encounter.id }); change() }) { Text("Select all") }
                TextButton(enabled = !state.busy, onClick = { selected = arrayListOf(); change() }) { Text("Select none") }
            }
            Text("${selected.count { id -> connections.any { it.encounter.id == id } }} selected")
        }
        items(connections, key = { it.encounter.id }) { connection ->
            Row(Modifier.fillMaxWidth()) {
                Checkbox(connection.encounter.id in selected, enabled = !state.busy, onCheckedChange = { checked ->
                    selected = ArrayList(if (checked) selected + connection.encounter.id else selected - connection.encounter.id); change()
                })
                Column { Text(connection.contact.name.ifBlank { connection.contact.company }); if (connection.contact.name.isNotBlank()) Text(connection.contact.company) }
            }
        }
        item {
            SectionHeading("2. Choose what to include")
            ExportToggle("Include company research and printed claims", includeResearch, !state.busy) { includeResearch = it; change() }
            ExportToggle("Include event context in Google Contacts", includeEvent, !state.busy) { includeEvent = it; change() }
            ExportToggle("Include private notes and follow-ups", includeNotes, !state.busy) { includeNotes = it; change() }
            ExportToggle("Include private conversation transcript", includeTranscript, !state.busy) { includeTranscript = it; change() }
            Text("CSV always includes event identifiers/dates as archive fields. Private notes and transcripts are excluded unless selected above.")
        }
        item {
            SectionHeading("3. Review & export")
            Button(enabled = selected.isNotEmpty() && !state.busy, onClick = { model.preview(snapshot(), "csv") }) { Text("Preview CSV export") }
            OutlinedButton(enabled = selected.isNotEmpty() && !state.busy, onClick = {
                model.preview(snapshot(), "google")
                val requested = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                if (requested.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) chooseAccount()
                else permissions.launch(requested)
            }) { Text("Choose Google account & preview") }
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working… You can leave the screen; completed Google writes are retained.") }
        if (state.message.isNotBlank()) item { Text(state.message) }
        if (state.destination == "csv" && state.snapshot != null) {
            item {
                Text("CSV preview", style = MaterialTheme.typography.titleLarge)
                Text("${state.snapshot!!.entries.size} rows. UTF-8 archive, not a Google-import template or full backup. Repeated fields are JSON arrays. Spreadsheet-sensitive cells receive a leading apostrophe; leading-zero numbers are protected. Photos and audio are not included.")
            }
            items(state.snapshot!!.entries, key = { "preview-${it.connection.encounter.id}" }) { entry ->
                var expanded by rememberSaveable { mutableStateOf(false) }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.connection.contact.name.ifBlank { entry.connection.contact.company })
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide fields" else "Show exported fields") }
                        if (expanded) ArchiveCsv.headers.zip(ArchiveCsv.values(entry, state.snapshot!!.options)).forEach { (key, value) -> Text("$key: $value") }
                    }
                }
            }
            item { Button(enabled = !state.busy, onClick = { createFile.launch("handshake-${event.id.take(8)}.csv") }) { Text("Save CSV file") } }
        }
        state.plan?.let { plan ->
            item {
                Text("Google Contacts preview", style = MaterialTheme.typography.titleLarge)
                Text("Account: ${plan.account}\n${plan.rows.size} unique contacts. No automatic messages are sent. A device write does not confirm cloud sync.")
            }
            items(plan.rows, key = { "google-${it.contact.id}" }) { row ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.contact.name.ifBlank { row.contact.company }, style = MaterialTheme.typography.titleMedium)
                        Text(row.warning)
                        listOf("Company" to row.contact.company, "Title" to row.contact.title,
                            "Phones" to row.contact.phone, "Emails" to row.contact.email, "Websites" to row.contact.website,
                            "Address" to row.contact.address, "Notes" to row.notes).forEach { (label, value) ->
                            Text("$label: ${value.ifBlank { "(empty)" }}")
                        }
                        if (row.duplicate && !row.blocked) ExportToggle("Create separately despite possible duplicate", row.contact.id in allowDuplicates, !state.busy) {
                            allowDuplicates = ArrayList(if (it) allowDuplicates + row.contact.id else allowDuplicates - row.contact.id)
                        }
                    }
                }
            }
            item { Button(enabled = !state.busy && plan.rows.any { !it.blocked && (!it.duplicate || it.contact.id in allowDuplicates) }, onClick = { confirmGoogle = true }) { Text("Export reviewed contacts") } }
        }
        items(state.outcomes) { result -> Text("${result.name}: ${result.message}${result.rawId?.let { " (record $it)" }.orEmpty()}") }
    }
    if (confirmGoogle && state.plan != null) AlertDialog(onDismissRequest = { confirmGoogle = false },
        title = { Text("Write to Google Contacts?") }, text = { Text("Create/update the reviewed contacts in ${state.plan!!.account}. Updates replace the contact fields and notes shown in the preview. Possible duplicates are skipped unless you chose to create them separately. Google cloud sync is handled by your device.") },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { confirmGoogle = false; model.exportGoogle(allowDuplicates.toSet()) }) { Text("Confirm export") } },
        dismissButton = { TextButton(onClick = { confirmGoogle = false }) { Text("Cancel") } })
}

@Composable
private fun ExportToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ChoiceRow(label, checked, enabled, onChange)
}
