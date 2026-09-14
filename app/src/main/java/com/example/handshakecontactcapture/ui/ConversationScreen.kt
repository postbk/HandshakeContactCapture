package com.example.handshakecontactcapture.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.handshakecontactcapture.HandshakeViewModel
import com.example.handshakecontactcapture.ai.SummaryContract
import com.example.handshakecontactcapture.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationScreen(model: HandshakeViewModel, connection: Connection, record: ConversationSummary?, saving: Boolean) {
    val id = connection.encounter.id
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val recording by model.recordingId.collectAsStateWithLifecycle()
    val playing by model.playingId.collectAsStateWithLifecycle()
    val transcribing = record?.state in listOf("transcription_queued", "transcription_running")
    val seconds by model.audioSeconds.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(record?.transcript.orEmpty()) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var reviewIndex by rememberSaveable { mutableIntStateOf(-1) }
    var reviewedText by rememberSaveable { mutableStateOf("") }
    var asFollowUp by rememberSaveable { mutableStateOf(false) }
    val busy = saving || recording != null || playing != null || transcribing
    val pending = record?.state in listOf("queued", "running")
    LaunchedEffect(record?.transcript) { if (!dirty) text = record?.transcript.orEmpty() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.stopAudio() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.stopAudio() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.startRecording(id)
        else model.report("Microphone permission was denied. You can still type or use keyboard voice typing.")
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading("Conversation summary", connection.contact.name.ifBlank { connection.contact.company })
        NotebookCard("What is worth remembering?", "Capture why this connection matters, what they need, what you promised, and the next step.")
        SectionHeading("1. Capture the conversation", "Record up to 59 seconds or type below. Audio stays on this device until you confirm upload. Leaving stops recording and keeps the audio.")
        if (recording == id) {
            StatPill("Recording: $seconds / 59 seconds", attention = true)
            LinearProgressIndicator(progress = { seconds / 59f }, modifier = Modifier.fillMaxWidth())
            Button(onClick = model::stopAudio, modifier = Modifier.fillMaxWidth()) { Text("Stop recording") }
        } else if (record?.audioPath.isNullOrBlank()) {
            Button(enabled = !busy, onClick = { confirmation = "record" }, modifier = Modifier.fillMaxWidth()) { Text("Record summary") }
        } else {
            StatPill("Saved audio")
            if (playing == id) Button(onClick = model::stopAudio) { Text("Stop playback") }
            else OutlinedButton(enabled = !busy, onClick = { model.playAudio(id, record!!.audioPath) }) { Text("Play recording") }
            OutlinedButton(enabled = !busy && !pending, onClick = { confirmation = "transcribe" }) { Text("Transcribe with OpenAI") }
            TextButton(enabled = !busy, onClick = { confirmation = "audio" }) { Text("Delete audio") }
        }
        if (transcribing) {
            Text(if (record?.state == "transcription_queued") "Transcription queued; waiting for a connection." else "OpenAI is transcribing…")
            TextButton(enabled = !saving, onClick = { model.cancelTranscription(id) }) { Text("Cancel transcription") }
        }
        SectionHeading("2. Review your summary")
        OutlinedTextField(text, { if (it.length <= 8000) { text = it; dirty = true } },
            enabled = !busy, label = { Text("Editable conversation summary") },
            supportingText = { Text("${text.length}/8000 characters. You can also use your keyboard's microphone.") },
            minLines = 5, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !busy, onClick = { model.saveSummary(id, text) { dirty = false } }, modifier = Modifier.fillMaxWidth()) { Text("Save summary locally") }
        SectionHeading("3. Find the next steps", "Turn your reviewed summary into suggested notes and follow-ups.")
        Button(enabled = !busy && !pending && text.isNotBlank(), onClick = { confirmation = "analyze" }, modifier = Modifier.fillMaxWidth()) { Text("Analyze summary") }
        if (pending) Text("Analysis ${record?.state}. Your saved text remains available offline.")
        if (!record?.error.isNullOrBlank()) Text(record!!.error, color = MaterialTheme.colorScheme.error)
        if (!record?.originalTranscript.isNullOrBlank()) {
            var showOriginal by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { showOriginal = !showOriginal }) { Text("Original transcript") }
            if (showOriginal) Text(record!!.originalTranscript)
        }
        if (!record?.result.isNullOrBlank()) {
            Text("Suggestions for review", style = MaterialTheme.typography.titleLarge)
            JSONObject(record!!.result).optJSONArray("validation_warnings")?.let { warnings ->
                for (index in 0 until warnings.length()) Text(warnings.getString(index), color = MaterialTheme.colorScheme.error)
            }
            Text("Conversation claims are not independently verified. Research comparisons use the saved research at analysis time.")
            Text("Analyzed ${DateFormat.getDateTimeInstance().format(Date(record!!.analyzedAt))}")
            val snapshot = remember(record.context) { JSONObject(record.context) }
            Text("Context: ${snapshot.optString("person")} / ${snapshot.optString("company")}")
            if (snapshot.optLong("researched_at") > 0) Text("Research dated ${DateFormat.getDateTimeInstance().format(Date(snapshot.getLong("researched_at")))}")
            if (record.context != SummaryContract.context(connection, state.research.find { it.encounterId == id }))
                Text("Contact or research context has changed since analysis. Check the sources or analyze again.")
            if (text != record.transcript) Text("The text has changed. Save and analyze again to update suggestions.")
            val suggestions = remember(record.result) { SummaryContract.parse(record.result, record.transcript, record.context) }
            val applied = JSONArray(record.applied)
            if (suggestions.isEmpty()) Text("No supported suggestions found. Add more detail if needed.")
            suggestions.forEachIndexed { index, item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.kind.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
                        Text(item.text)
                        Text("From your summary: “${item.evidence}”")
                        item.recipient?.let { Text("Who: $it") }
                        item.timing?.let { Text("Timing: $it") }
                        item.sources.forEach { url -> TextButton(onClick = {
                            runCatching { uriHandler.openUri(url) }.onFailure { model.report("Could not open source.") }
                        }) { Text(url) } }
                        if ((0 until applied.length()).any { applied.getInt(it) == index }) Text("Added to connection")
                        else OutlinedButton(enabled = !busy && text == record.transcript, onClick = {
                            reviewIndex = index; reviewedText = item.noteText()
                            asFollowUp = item.kind in listOf("follow_up", "introduction", "promised_material")
                        }) { Text("Review suggestion") }
                    }
                }
            }
        }
        if (record != null) TextButton(enabled = !busy, onClick = { confirmation = "delete" }) { Text("Delete conversation summary") }
    }
    if (reviewIndex >= 0 && record != null) AlertDialog(onDismissRequest = { if (!saving) reviewIndex = -1 },
        title = { Text("Add to connection") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(reviewedText, { if (it.length <= 4000) reviewedText = it }, label = { Text("Reviewed suggestion") }, minLines = 4)
                Row { Checkbox(asFollowUp, { asFollowUp = it }); Text("Add to follow-up list") }
                Text("This appends to your current ${if (asFollowUp) "follow-ups and marks the list open" else "notes"}.")
            }
        }, confirmButton = { TextButton(enabled = !saving && reviewedText.isNotBlank(), onClick = {
            model.applySummary(id, record.requestId, reviewIndex, reviewedText, asFollowUp) { reviewIndex = -1 }
        }) { Text("Add reviewed suggestion") } }, dismissButton = { TextButton(enabled = !saving, onClick = { reviewIndex = -1 }) { Text("Cancel") } })
    if (confirmation.isNotBlank()) AlertDialog(onDismissRequest = { confirmation = "" },
        title = { Text(when (confirmation) {
            "record" -> "Record a private summary?"
            "transcribe" -> "Upload audio to OpenAI?"
            "analyze" -> "Send summary to OpenAI?"
            else -> "Delete saved content?"
        }) }, text = { Text(when (confirmation) {
            "record" -> "Handshake needs microphone access. The recording is stored privately on this device and stops after 59 seconds."
            "transcribe" -> "Upload this saved recording to OpenAI using your API key. API charges apply, including retries; provider retention policies apply. Your audio stays saved locally. The transcript will be appended to this draft for review. Previous unaccepted analysis suggestions will be cleared; accepted notes and tasks remain. You can leave the screen while it processes."
            "analyze" -> "Send this edited text, contact name/company, printed claims, and saved company research to OpenAI using your saved API key. Audio is not sent to OpenAI. API charges may apply, including retries. Private text is not used in public web searches. Suggestions require review. Reanalysis replaces unaccepted suggestions; notes already added remain."
            "audio" -> "Delete this recording from this device? Transcript and notes will remain."
            else -> "Delete the saved audio, transcript, and suggestions and cancel analysis? Notes and follow-ups already added to the connection remain."
        }) }, confirmButton = { TextButton(onClick = {
            val action = confirmation; confirmation = ""
            when (action) {
                "record" -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    model.startRecording(id) else permission.launch(Manifest.permission.RECORD_AUDIO)
                "transcribe" -> model.transcribeAudio(id, text) { dirty = false }
                "analyze" -> { dirty = false; model.analyzeSummary(id, text) }
                "audio" -> model.deleteAudio(id)
                "delete" -> model.deleteSummary(id) { text = ""; dirty = false }
            }
        }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirmation = "" }) { Text("Cancel") } })
}
