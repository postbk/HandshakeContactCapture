package com.example.handshakecontactcapture

import android.app.Application
import android.net.Uri
import com.example.handshakecontactcapture.ai.*
import com.example.handshakecontactcapture.capture.PhotoStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

import com.example.handshakecontactcapture.capture.MemoAudio


data class HandshakeState(val loading: Boolean = true, val events: List<Event> = emptyList(),
    val connections: List<Connection> = emptyList(), val error: String? = null,
    val captures: List<Capture> = emptyList(), val research: List<Research> = emptyList())

class HandshakeViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = HandshakeDatabase.get(application).dao()
    private val photos = PhotoStorage(application)
    private val jobs = AiJobs(application)
    val settings = AiSettings(application)
    private val problem = MutableStateFlow<String?>(null)
    val saving = MutableStateFlow(false)
    val summaries = dao.summaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val audio = MemoAudio(application)
    val recordingId = MutableStateFlow<String?>(null)
    val audioSeconds = MutableStateFlow(0)
    val playingId = MutableStateFlow<String?>(null)

    private var playback: Job? = null

    fun stopAudio() { audio.stop(); playback?.cancel() }
    fun startRecording(id: String) {
        if (recordingId.value != null || saving.value) return
        audio.reset()
        recordingId.value = id
        audioSeconds.value = 0
        write {
            try {
                val record = dao.summary(id) ?: ConversationSummary(id)
                if (record.audioPath.isNotEmpty()) throw AiFailure("Delete the existing audio before recording again.")
                val name = "${newId()}.pcm"
                dao.saveSummary(record.copy(audioPath = name))
                audio.record(name) { audioSeconds.value = it }
                if (withContext(Dispatchers.IO) { audio.file(name).length() } < 3200)
                    throw AiFailure("The recording was too short. Delete the audio and try again, or type a summary.")
            } finally { recordingId.value = null }
        }
    }
    fun playAudio(id: String, name: String) {
        playback?.cancel()
        playback = viewModelScope.launch {
            playingId.value = id
            try { audio.play(name) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { report("Could not play this recording. Your summary text is still available.") }
            finally { playingId.value = null }
        }
    }
    fun deleteAudio(id: String) = write {
        stopAudio()
        val record = dao.summary(id) ?: return@write
        dao.saveSummary(if (record.state.startsWith("transcription_")) record.copy(audioPath = "", state = "draft", requestId = newId()) else record.copy(audioPath = ""))
        jobs.cancel("transcription", id)
        if (record.audioPath.isNotBlank()) withContext(Dispatchers.IO) { audio.file(record.audioPath).delete() }
    }
    fun transcribeAudio(id: String, draft: String, onQueued: () -> Unit) = write(onQueued) {
        if (!settings.hasKey) throw AiFailure("Add your OpenAI key in AI settings first.")
        require(draft.length <= 8000)
        val record = dao.summary(id) ?: return@write
        if (record.audioPath.isBlank()) throw AiFailure("Record a summary first.")
        if (record.state in listOf("transcription_queued", "transcription_running")) return@write
        val queued = ConversationSummary(id, draft, record.originalTranscript, state = "transcription_queued",
            model = settings.transcriptionModel, audioPath = record.audioPath)
        dao.saveSummary(queued)
        jobs.cancel("summary", id)
        jobs.cancel("transcription", id)
        jobs.enqueue("transcription", id, queued.requestId)
    }
    fun cancelTranscription(id: String) = write {
        val record = dao.summary(id) ?: return@write
        if (record.state.startsWith("transcription_")) dao.saveSummary(record.copy(state = "draft", requestId = newId(), error = ""))
        jobs.cancel("transcription", id)
    }
    fun saveSummary(id: String, text: String, onSaved: () -> Unit = {}) = write(onSaved) {
        require(text.length <= 8000)
        val record = dao.summary(id) ?: ConversationSummary(id)
        if (record.transcript != text) {
            dao.saveSummary(ConversationSummary(id, text, record.originalTranscript, audioPath = record.audioPath))
            jobs.cancel("summary", id)
        jobs.cancel("transcription", id)
        } else dao.saveSummary(record)
    }
    fun analyzeSummary(id: String, text: String) = write {
        if (!settings.hasKey) throw AiFailure("Add your OpenAI key in AI settings first.")
        require(text.isNotBlank() && text.length <= 8000)
        val connection = dao.connection(id) ?: return@write
        val old = dao.summary(id)
        val context = SummaryContract.context(connection, dao.researchFor(id))
        val record = ConversationSummary(id, text, old?.originalTranscript.orEmpty(),
            state = "queued", context = context, model = settings.summaryModel, audioPath = old?.audioPath.orEmpty())
        dao.saveSummary(record)
        jobs.cancel("summary", id)
        jobs.cancel("transcription", id)
        jobs.enqueue("summary", id, record.requestId)
    }
    fun applySummary(id: String, request: String, index: Int, text: String, followUp: Boolean, onSaved: () -> Unit) =
        write(onSaved) { dao.applySummary(id, request, index, text, followUp) }
    fun deleteSummary(id: String, onDeleted: () -> Unit) = write(onDeleted) {
        stopAudio()
        val record = dao.summary(id)
        dao.deleteSummary(id)
        jobs.cancel("summary", id)
        jobs.cancel("transcription", id)
        record?.audioPath?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { audio.file(it).delete() } }
    }
    val state = combine(dao.events(), dao.connections(), problem, dao.captures(), dao.research()) { events, connections, error, captures, research ->
        HandshakeState(false, events, connections, error, captures, research)
    }.catch { emit(HandshakeState(loading = false, error = "Could not open saved contacts. Please restart the app.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HandshakeState())
    init { viewModelScope.launch { runCatching { jobs.recover(dao) } } }
    private fun write(onSaved: () -> Unit = {}, block: suspend () -> Unit) {
        if (saving.value) return
        saving.value = true
        viewModelScope.launch {
            try { block(); problem.value = null; onSaved() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { problem.value = (error as? AiFailure)?.message ?: "Could not save your change. Please try again." }
            finally { saving.value = false }
        }
    }
    fun saveEvent(event: Event, onSaved: () -> Unit) = write(onSaved) { dao.saveEvent(event) }
    fun save(contact: Contact, encounter: Encounter, onSaved: () -> Unit) = write(onSaved) { dao.saveConnection(contact, encounter) }
    fun toggle(connection: Connection) = write { dao.saveEncounter(connection.encounter.copy(done = !connection.encounter.done)) }
    fun delete(connection: Connection, onSaved: () -> Unit) = write(onSaved) {
        stopAudio()
        val memo = dao.summary(connection.encounter.id)
        dao.deleteConnection(connection.encounter.id)
        jobs.cancel("research", connection.encounter.id)
        jobs.cancel("summary", connection.encounter.id)
        jobs.cancel("transcription", connection.encounter.id)
        memo?.audioPath?.takeIf { it.isNotBlank() }?.let { withContext(Dispatchers.IO) { audio.file(it).delete() } }
    }
    fun report(message: String) { problem.value = message }
    fun createCapture(eventId: String, onCreated: (String) -> Unit) {
        val capture = Capture(eventId = eventId)
        write({ onCreated(capture.id) }) { dao.insertCapture(capture) }
    }
    fun addPhoto(id: String, uri: Uri, onImported: () -> Unit = {}) = write(onImported) {
        val capture = dao.capture(id) ?: throw AiFailure("This photo batch no longer exists.")
        val images = jsonStrings(capture.images)
        if (capture.state != "draft" || images.size >= 4) throw AiFailure("Start another batch to add more photos.")
        val filename = withContext(Dispatchers.IO) { photos.import(uri) }
        try { check(dao.updateCapture(capture.copy(images = JSONArray(images + filename).toString())) == 1) }
        catch (e: Exception) { withContext(Dispatchers.IO) { photos.delete(filename) }; throw e }
    }
    fun removePhoto(id: String, filename: String) = write {
        val capture = dao.capture(id) ?: return@write
        if (capture.state != "draft") return@write
        dao.updateCapture(capture.copy(images = JSONArray(jsonStrings(capture.images) - filename).toString()))
        withContext(Dispatchers.IO) { photos.delete(filename) }
    }
    fun extract(id: String) = write {
        if (!settings.hasKey) throw AiFailure("Add your OpenAI key in AI settings first.")
        val capture = dao.capture(id) ?: return@write
        if (capture.state !in listOf("draft", "failed") || jsonStrings(capture.images).isEmpty()) return@write
        jobs.cancel("extract", id)
        dao.updateCapture(capture.copy(state = "queued", error = "", model = settings.extractionModel))
        jobs.enqueue("extract", id)
    }
    fun deleteCapture(id: String, onDeleted: () -> Unit) = write(onDeleted) {
        val capture = dao.capture(id) ?: return@write
        dao.deleteCapture(id)
        jobs.cancel("extract", id)
        withContext(Dispatchers.IO) { jsonStrings(capture.images).forEach { photos.delete(it) } }
    }
    fun accept(captureId: String, contact: Contact, encounter: Encounter, onSaved: () -> Unit) = write(onSaved) {
        dao.acceptCandidate(captureId, contact, encounter)
    }
    fun research(connection: Connection, company: String, website: String, location: String) = write {
        if (!settings.hasKey) throw AiFailure("Add your OpenAI key in AI settings first.")
        if (company.isBlank()) throw AiFailure("Enter the company name first.")
        if (company.length > 500 || website.length > 2000 || location.length > 500) {
            throw AiFailure("Shorten the company name, website or location before researching.")
        }
        val existing = dao.researchFor(connection.encounter.id)
        if (existing?.state in listOf("queued", "running")) return@write
        val record = Research(connection.encounter.id,
            AiContract.researchIdentity(company, website, location, connection.encounter.printedClaims),
            model = settings.researchModel)
        jobs.cancel("research", connection.encounter.id)
        dao.saveResearch(if (record.identity == existing?.identity) record.copy(
            summary = existing.summary, citations = existing.citations, researchedAt = existing.researchedAt) else record)
        jobs.enqueue("research", connection.encounter.id, record.requestId)
    }
}
