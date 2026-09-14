package com.example.handshakecontactcapture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.handshakecontactcapture.data.*
import com.example.handshakecontactcapture.ai.AiContract
import com.example.handshakecontactcapture.ai.jsonStrings
import com.example.handshakecontactcapture.ui.*
import com.example.handshakecontactcapture.ui.theme.HandshakeContactCaptureTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HandshakeContactCaptureTheme { HandshakeApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandshakeApp(model: HandshakeViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val summaries by model.summaries.collectAsStateWithLifecycle()
    var eventId by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var newEvent by rememberSaveable { mutableStateOf(false) }
    var editingEvent by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var followUpsOnly by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var captureId by rememberSaveable { mutableStateOf<String?>(null) }
    var candidateIndex by rememberSaveable { mutableIntStateOf(0) }
    val capture = state.captures.find { it.id == captureId }
    val event = state.events.find { it.id == eventId }
    val selected = state.connections.find { it.encounter.id == selectedId }
    val connections = state.connections.filter { it.encounter.eventId == eventId }
    val back = { screen = if (screen == "review") "capture" else "home"; selectedId = null }
    BackHandler(screen != "home" || eventId != null) {
        if (screen != "home") back() else eventId = null
    }
    Scaffold(
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { BrandMark(34); Text("Handshake", style = MaterialTheme.typography.titleLarge) } },
            actions = { TextButton(onClick = { showSettings = true }) { Text("AI settings") } },
            navigationIcon = { if (eventId != null || screen != "home") TextButton(onClick = {
                if (screen != "home") back() else eventId = null
            }) { Text("Back") } }) },
        floatingActionButton = {
            if (screen == "home" && !state.loading) ExtendedFloatingActionButton(onClick = {
                if (event == null) newEvent = true else { selectedId = null; screen = "edit" }
            }) { Text(if (event == null) "+ New event" else "+ Add connection") }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
                screen == "export" && event != null -> ExportScreen(event, connections, state.research, summaries, selectedId)
                screen == "capture" && capture != null -> CaptureScreen(capture, state.connections, saving,
                    onAdd = { uri, finished -> model.addPhoto(capture.id, uri, finished) },
                    onRemove = { model.removePhoto(capture.id, it) }, onExtract = { model.extract(capture.id) },
                    onReview = { candidateIndex = it; screen = "review" },
                    onDelete = { model.deleteCapture(capture.id) { screen = "home"; captureId = null } },
                    onSettings = { showSettings = true }, onError = model::report)
                screen == "review" && capture != null && event != null -> {
                    val candidate = remember(capture.result, candidateIndex) { AiContract.candidates(capture.result)[candidateIndex] }
                    val id = "${capture.id}-$candidateIndex"
                    val draft = Connection(Encounter(id = id, eventId = event.id, contactId = id,
                        captureId = capture.id, printedClaims = candidate.printedClaims), candidate.contact.copy(id = id))
                    key(id) {
                        ConnectionEditor(event, draft, saving, onCancel = back,
                            onSave = { contact, encounter -> model.accept(capture.id, contact, encounter) { screen = "capture" } },
                            extra = {
                                Text("Check the extracted values against the source before saving.")
                                jsonStrings(capture.images).firstOrNull()?.let { PhotoPreview(it, "Original source") }
                                if (candidate.warnings.isNotBlank()) Text(candidate.warnings, color = MaterialTheme.colorScheme.error)
                                if (candidate.evidence.isNotBlank()) Text(candidate.evidence)
                            })
                    }
                }
                screen == "edit" && event != null -> ConnectionEditor(event, selected, saving,
                    onCancel = back, onSave = { contact, encounter -> model.save(contact, encounter, back) })
                screen == "summary" && selected != null -> key(selected.encounter.id) {
                    ConversationScreen(model, selected, summaries.find { it.encounterId == selected.encounter.id }, saving)
                }
                screen == "detail" && selected != null -> ConnectionDetail(selected, saving,
                    onEdit = { screen = "edit" }, onToggle = { model.toggle(selected) },
                    onDelete = { model.delete(selected, back) }, extra = {
                        Button(onClick = { screen = "summary" }, modifier = Modifier.fillMaxWidth()) { Text("Conversation summary") }
                        OutlinedButton(onClick = { screen = "export" }, modifier = Modifier.fillMaxWidth()) { Text("Export connection") }
                        if (state.captures.any { it.id == selected.encounter.captureId }) {
                            OutlinedButton(onClick = { captureId = selected.encounter.captureId; screen = "capture" }) { Text("View source photos") }
                        }
                        ResearchPanel(selected, state.research.find { it.encounterId == selected.encounter.id },
                            onResearch = { company, website, location -> model.research(selected, company, website, location) },
                            onSettings = { showSettings = true })
                    })
                event == null -> EventsOverview(state.events, state.connections) { savedEvent ->
                    eventId = savedEvent.id; query = ""; followUpsOnly = false
                }
                else -> EventNotebook(event, connections, state.captures.filter { it.eventId == event.id }, saving,
                    query, { query = it }, followUpsOnly, { followUpsOnly = !followUpsOnly },
                    onEdit = { editingEvent = true }, onExport = { selectedId = null; screen = "export" },
                    onScan = { model.createCapture(event.id) { captureId = it; screen = "capture" } },
                    onCapture = { captureId = it.id; screen = "capture" },
                    onContact = { selectedId = it.encounter.id; screen = "detail" })
            }
        }
        }
    }
    if (newEvent) EventDialog(saving, onDismiss = { newEvent = false }) { name, location, dates ->
        val created = Event(name = name.trim(), location = location.trim(), dates = dates.trim())
        model.saveEvent(created) { newEvent = false; eventId = created.id }
    }
    if (showSettings) AiSettingsDialog(model.settings) { showSettings = false }
    if (editingEvent && event != null) EventDialog(saving, onDismiss = { editingEvent = false }, original = event) { name, location, dates ->
        model.saveEvent(event.copy(name = name.trim(), location = location.trim(), dates = dates.trim())) {
            editingEvent = false
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EventDialog(saving: Boolean, onDismiss: () -> Unit, original: Event? = null, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var location by rememberSaveable(original?.id) { mutableStateOf(original?.location.orEmpty()) }
    var dates by rememberSaveable(original?.id) { mutableStateOf(original?.dates.orEmpty()) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (original == null) "New event" else "Edit event") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Event name *", name, { name = it })
            Field("Location", location, { location = it })
            Field("Dates (optional)", dates, { dates = it })
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, location, dates) }, enabled = name.isNotBlank() && !saving) { Text(if (saving) "Saving…" else if (original == null) "Create event" else "Save event") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } })
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, multiline: Boolean = false) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = !multiline, minLines = if (multiline) 3 else 1)
}

@Composable
private fun ConnectionEditor(event: Event, original: Connection?, saving: Boolean,
    onCancel: () -> Unit, onSave: (Contact, Encounter) -> Unit, extra: @Composable () -> Unit = {}) {
    val contact = original?.contact
    val encounter = original?.encounter
    var name by rememberSaveable { mutableStateOf(contact?.name.orEmpty()) }
    var company by rememberSaveable { mutableStateOf(contact?.company.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(contact?.title.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(contact?.email.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(contact?.phone.orEmpty()) }
    var website by rememberSaveable { mutableStateOf(contact?.website.orEmpty()) }
    var address by rememberSaveable { mutableStateOf(contact?.address.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(encounter?.notes.orEmpty()) }
    var followUp by rememberSaveable { mutableStateOf(encounter?.followUp.orEmpty()) }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading(if (original == null) "New connection" else "Edit connection", event.name)
        extra()
        SectionHeading("The introduction", "A name or company is all you need. Everything else is optional.")
        Field("Full name", name, { name = it })
        Field("Company", company, { company = it })
        Field("Job title", title, { title = it })
        SectionHeading("Contact details")
        Field("Email (one per line)", email, { email = it }, true)
        Field("Phone (one per line)", phone, { phone = it }, true)
        Field("Website (one per line)", website, { website = it }, true)
        Field("Address", address, { address = it }, true)
        SectionHeading("Remember & follow up", "Private notes stay in your notebook unless you include them in an export.")
        Field("Capabilities & meeting notes", notes, { notes = it }, true)
        Field("Next step / who to connect them to", followUp, { followUp = it }, true)
        Button(onClick = {
            val updated = Contact(contact?.id ?: newId(), name.trim(), company.trim(), title.trim(), email.trim(), phone.trim(), website.trim(), address.trim())
            onSave(updated, Encounter(encounter?.id ?: newId(), event.id, updated.id, notes.trim(), followUp.trim(),
                if (followUp.trim() == encounter?.followUp) encounter.done else false,
                encounter?.createdAt ?: System.currentTimeMillis(), encounter?.captureId.orEmpty(), encounter?.printedClaims.orEmpty()))
        }, enabled = !saving && (name.isNotBlank() || company.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
            Text(if (saving) "Saving…" else "Save connection")
        }
        TextButton(onClick = onCancel, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun ConnectionDetail(connection: Connection, saving: Boolean, onEdit: () -> Unit,
    onToggle: () -> Unit, onDelete: () -> Unit, extra: @Composable () -> Unit = {}) {
    var confirmDelete by remember { mutableStateOf(false) }
    val contact = connection.contact
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialBadge(contact.name.ifBlank { contact.company })
            Column(Modifier.weight(1f)) {
                Text(contact.name.ifBlank { contact.company }, style = MaterialTheme.typography.headlineMedium)
                val subtitle = listOf(contact.title, contact.company.takeIf { contact.name.isNotBlank() }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val details = listOf("Email" to contact.email, "Phone" to contact.phone, "Website" to contact.website, "Address" to contact.address).filter { it.second.isNotBlank() }
        if (details.isNotEmpty()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                details.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(value) }
                    }
                }
            }
        }
        if (connection.encounter.notes.isNotBlank()) NotebookCard("Meeting notes", connection.encounter.notes)
        if (connection.encounter.followUp.isNotBlank()) {
            NotebookCard("Next step", connection.encounter.followUp)
            OutlinedButton(onClick = onToggle, enabled = !saving) {
                Text(if (connection.encounter.done) "Completed · Reopen follow-up" else "Mark follow-up complete")
            }
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Edit connection") }
        extra()
        TextButton(onClick = { confirmDelete = true }, enabled = !saving) { Text("Delete connection", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this connection?") },
        text = { Text("This removes this meeting and its notes from your event.") },
        confirmButton = { TextButton(onClick = onDelete, enabled = !saving) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
