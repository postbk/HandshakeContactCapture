package com.example.handshakecontactcapture.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.handshakecontactcapture.ai.*
import com.example.handshakecontactcapture.capture.PhotoStorage
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun AiSettingsDialog(settings: AiSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(settings.hasKey) }
    var extraction by remember { mutableStateOf(settings.extractionModel) }
    var research by remember { mutableStateOf(settings.researchModel) }
    var summary by remember { mutableStateOf(settings.summaryModel) }
    var transcription by remember { mutableStateOf(settings.transcriptionModel) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val wasSecure = (window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        title = { Text("OpenAI settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Use your own OpenAI API key. It is encrypted on this device; API usage is billed to your OpenAI account.")
                Text("Device storage is intended for your personal prototype. A compromised device can expose a usable key.")
                Text(if (hasKey) "A key is saved. Leave the field blank to keep it." else "No key saved yet.")
                OutlinedTextField(key, { key = it }, label = { Text(if (hasKey) "Replace API key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(extraction, { extraction = it }, label = { Text("Extraction model") }, singleLine = true)
                OutlinedTextField(research, { research = it }, label = { Text("Research model") }, singleLine = true)
                OutlinedTextField(summary, { summary = it }, label = { Text("Conversation summary model") }, singleLine = true)
                OutlinedTextField(transcription, { transcription = it }, label = { Text("Audio transcription model") }, singleLine = true)
                Text("Transcription uploads saved audio to OpenAI after confirmation. Summary analysis separately sends your reviewed text and saved company research.")
                Text("Extraction uploads the selected photos. Research sends the company identity and printed capabilities. Private meeting notes are excluded.")
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (hasKey) TextButton(enabled = !saving, onClick = {
                    scope.launch {
                        saving = true
                        try { withContext(Dispatchers.IO) { settings.remove() }; hasKey = false; key = "" }
                        catch (_: Exception) { error = "Could not remove the saved key." }
                        finally { saving = false }
                    }
                }) { Text("Remove saved key") }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && (hasKey || key.isNotBlank()), onClick = {
                scope.launch {
                    saving = true
                    try {
                        withContext(Dispatchers.IO) { settings.save(key, extraction.trim(), research.trim(), summary.trim(), transcription.trim()) }
                        key = ""
                        onDismiss()
                    } catch (e: IllegalArgumentException) { error = e.message ?: "Check your key and model IDs." }
                    catch (_: Exception) { error = "Could not encrypt and save the key. Please try again." }
                    finally { saving = false }
                }
            }) { Text(if (saving) "Saving..." else "Save settings") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Close") } })
}

@Composable
fun CaptureScreen(capture: Capture, connections: List<Connection>, busy: Boolean,
    onAdd: (Uri, () -> Unit) -> Unit, onRemove: (String) -> Unit, onExtract: () -> Unit,
    onReview: (Int) -> Unit, onDelete: () -> Unit, onSettings: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val photos = remember { PhotoStorage(context) }
    val scope = rememberCoroutineScope()
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmUpload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAdd(uri) {}
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPath?.let { path ->
            val file = File(path)
            if (success) onAdd(Uri.fromFile(file)) { file.delete(); cameraPath = null }
            else { file.delete(); cameraPath = null }
        }
    }
    val images = remember(capture.images) { jsonStrings(capture.images) }
    val candidates = remember(capture.result) {
        if (capture.result.isBlank()) emptyList() else runCatching { AiContract.candidates(capture.result) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeading("Card / fact sheet", "Add up to four photos. Keep text sharp and fill the frame.")
        StatPill(when(capture.state) {
            "draft" -> "Saved on device"
            "queued" -> "Queued - waiting for a connection or retry"
            "running" -> "Extracting contact details"
            "review" -> "Ready to review"
            else -> "Needs attention"
        }, attention = capture.state == "failed")
        if (capture.state in listOf("queued", "running")) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (capture.error.isNotBlank()) Text(capture.error, color = MaterialTheme.colorScheme.error)
        images.forEachIndexed { index, name ->
            PhotoPreview(name, "Source photo ${index + 1}")
            if (capture.state == "draft") TextButton(onClick = { onRemove(name) }, enabled = !busy) { Text("Remove photo ${index + 1}") }
        }
        if (capture.state == "draft" && images.size < 4) {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { photos.cameraFile() }
                    cameraPath = file.path
                    try { camera.launch(photos.cameraUri(file)) }
                    catch (_: ActivityNotFoundException) { file.delete(); cameraPath = null; onError("No camera app is available. Use Choose photo instead.") }
                    catch (_: SecurityException) { file.delete(); cameraPath = null; onError("Camera access was denied. Use Choose photo instead.") }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Take photo") }
            OutlinedButton(enabled = !busy, onClick = { pick.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text("Choose photo") }
        }
        if (capture.state in listOf("draft", "failed")) Button(
            enabled = !busy && images.isNotEmpty(), onClick = { confirmUpload = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (capture.state == "failed") "Retry extraction" else "Extract contact details")
        }
        if (capture.state == "review") {
            SectionHeading("Review before saving", "Check each person against the photos. Missing values are left blank.")
            val warnings = runCatching { JSONObject(capture.result).getJSONArray("warnings").let { jsonStrings(it.toString()).joinToString("\n") } }.getOrDefault("")
            if (warnings.isNotBlank()) Text(warnings, color = MaterialTheme.colorScheme.error)
            if (candidates.isEmpty()) Text("No usable contact details were found. Create another batch with clearer photos, or add the connection manually.")
            candidates.forEachIndexed { index, candidate ->
                val saved = connections.any { it.encounter.id == "${capture.id}-$index" }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(candidate.contact.name.ifBlank { candidate.contact.company }, style = MaterialTheme.typography.titleMedium)
                        Text(candidate.contact.company)
                        if (candidate.evidence.isNotBlank()) Text("Printed evidence: ${candidate.evidence}")
                        if (candidate.warnings.isNotBlank()) Text(candidate.warnings, color = MaterialTheme.colorScheme.error)
                        Button(enabled = !saved && !busy, onClick = { onReview(index) }) { Text(if (saved) "Saved to event" else "Review & save") }
                    }
                }
            }
        }
        TextButton(onClick = onSettings) { Text("AI settings") }
        TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("Delete photo batch", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmUpload) AlertDialog(onDismissRequest = { confirmUpload = false }, title = { Text("Send photos to OpenAI?") },
        text = { Text("These ${images.size} photos will be uploaded using your saved API key to extract contact information. API charges apply. You can leave this screen while it processes.") },
        confirmButton = { TextButton(onClick = { confirmUpload = false; onExtract() }) { Text("Upload & extract") } },
        dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("Cancel") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete photos and extraction?") },
        text = { Text("This removes the saved source photos and extracted suggestions and cancels pending extraction. Contacts already saved to the event remain.") },
        confirmButton = { TextButton(onClick = onDelete, enabled = !busy) { Text("Delete batch") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
fun PhotoPreview(name: String, description: String) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { runCatching { PhotoStorage(context).preview(name) }.getOrNull() }
    }
    var expanded by remember { mutableStateOf(false) }
    bitmap?.let { image ->
        Image(image.asImageBitmap(), description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(240.dp).clickable { expanded = true })
        if (expanded) androidx.compose.ui.window.Dialog(onDismissRequest = { expanded = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    TextButton(onClick = { expanded = false }) { Text("Close photo") }
                    Image(image.asImageBitmap(), description, modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
        }
    }
}

@Composable
fun ResearchPanel(connection: Connection, research: Research?, onResearch: (String, String, String) -> Unit, onSettings: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var company by rememberSaveable(connection.encounter.id) { mutableStateOf(connection.contact.company) }
    var website by rememberSaveable(connection.encounter.id) { mutableStateOf(connection.contact.website.lineSequence().firstOrNull().orEmpty()) }
    var location by rememberSaveable(connection.encounter.id) { mutableStateOf("") }
    val processing = research?.state in listOf("queued", "running")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading("Company research", "Sourced findings, kept separate from your meeting notes.")
            if (connection.encounter.printedClaims.isNotBlank()) {
                Text("From the card / fact sheet", style = MaterialTheme.typography.titleSmall)
                Text(connection.encounter.printedClaims)
            }
            if (research == null) Text("Confirm the company, then get a sourced summary of what it does and how the printed capabilities compare.")
            if (processing) { Text("Research queued or in progress. You can return later."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (research?.error?.isNotBlank() == true) Text(research.error, color = MaterialTheme.colorScheme.error)
            if (research?.summary?.isNotBlank() == true) {
                val identity = remember(research.identity) { JSONObject(research.identity) }
                Text("Researched: ${identity.optString("company")} · ${identity.optString("website")}")
                if (identity.optString("company") != connection.contact.company ||
                    identity.optString("website") != connection.contact.website.lineSequence().firstOrNull().orEmpty()) {
                    Text("Company details may have changed since this research. Review the identity before relying on this summary.")
                }
                CitedResearch(research)
                Text("Updated ${DateFormat.getDateTimeInstance().format(Date(research.researchedAt))}")
            }
            Button(enabled = !processing, onClick = {
                company = connection.contact.company
                website = connection.contact.website.lineSequence().firstOrNull().orEmpty()
                confirm = true
            }) { Text(if (research == null) "Research company" else "Refresh / retry research") }
            TextButton(onClick = onSettings) { Text("AI settings") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm the company") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Check these details to avoid researching a similarly named company. The company identity and printed capabilities will be sent to OpenAI; private notes are excluded. API and web-search charges apply.")
            OutlinedTextField(company, { company = it }, label = { Text("Company name") })
            OutlinedTextField(website, { website = it }, label = { Text("Official website (if known)") })
            OutlinedTextField(location, { location = it }, label = { Text("Company city / country (optional)") })
        }
    }, confirmButton = { TextButton(enabled = company.isNotBlank(), onClick = { confirm = false; onResearch(company, website, location) }) { Text("Confirm & research") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun CitedResearch(research: Research) {
    val color = MaterialTheme.colorScheme.primary
    val text = remember(research.summary, research.citations, color) {
        buildAnnotatedString {
            var cursor = 0
            AiContract.citations(research.citations).sortedBy { it.start }.forEachIndexed { index, citation ->
                if (validWebUrl(citation.url) && citation.start >= cursor && citation.end <= research.summary.length && citation.end > citation.start) {
                    append(research.summary.substring(cursor, citation.start))
                    withLink(LinkAnnotation.Url(citation.url, TextLinkStyles(SpanStyle(color = color,
                        textDecoration = TextDecoration.Underline)))) {
                        val citedText = research.summary.substring(citation.start, citation.end)
                        // An annotation may cover prose, not just a citation marker. Preserve that prose.
                        if (!(citedText.startsWith("cite") && citedText.endsWith(""))) append(citedText)
                        append("[${index + 1}]")
                    }
                    cursor = citation.end
                }
            }
            append(research.summary.substring(cursor))
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    AiContract.citations(research.citations).distinctBy { it.url }.forEach { citation ->
        if (validWebUrl(citation.url)) Text(buildAnnotatedString {
            withLink(LinkAnnotation.Url(citation.url)) { append(citation.title.ifBlank { citation.url }) }
        }, color = color, style = MaterialTheme.typography.bodySmall)
    }
}
