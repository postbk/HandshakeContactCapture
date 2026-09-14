package com.example.handshakecontactcapture.ai

import android.content.Context
import androidx.work.*
import com.example.handshakecontactcapture.capture.PhotoStorage
import com.example.handshakecontactcapture.capture.MemoAudio
import com.example.handshakecontactcapture.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Executes against injected storage/provider so device tests never require a real API key. */
class AiJobRunner(private val dao: HandshakeDao, private val photos: PhotoStorage, private val provider: AiProvider, private val audio: MemoAudio? = null) {
    suspend fun transcribe(id: String, requestId: String) {
        val record = dao.summary(id) ?: return
        if (record.requestId != requestId || record.state !in listOf("transcription_queued", "transcription_running")) return
        dao.finishSummary(id, requestId, "transcription_running", "", "", 0)
        val text = provider.transcribe(record.model, requireNotNull(audio).file(record.audioPath))
        currentCoroutineContext().ensureActive()
        dao.finishTranscription(id, requestId, text)
    }
    suspend fun summarize(id: String, requestId: String) {
        val record = dao.summary(id) ?: return
        if (record.requestId != requestId || record.state !in listOf("queued", "running")) return
        dao.finishSummary(id, requestId, "running", record.result, "", record.analyzedAt)
        val result = provider.summarize(record.model, record.transcript, record.context)
        SummaryContract.parse(result, record.transcript, record.context)
        currentCoroutineContext().ensureActive()
        dao.finishSummary(id, requestId, "review", result, "", System.currentTimeMillis())
    }
    suspend fun extract(id: String) {
        val capture = dao.capture(id) ?: return
        if (capture.state !in listOf("queued", "running")) return
        dao.updateCapture(capture.copy(state = "running", error = ""))
        val result = provider.extract(capture.model, jsonStrings(capture.images).map { photos.file(it) })
        currentCoroutineContext().ensureActive()
        dao.updateCapture(capture.copy(state = "review", result = result, error = ""))
    }
    suspend fun research(id: String, requestId: String) {
        val record = dao.researchFor(id) ?: return
        if (record.requestId != requestId || record.state !in listOf("queued", "running")) return
        dao.finishResearch(id, requestId, "running", record.summary, record.citations, "", record.researchedAt)
        val result = provider.research(record.model, record.identity)
        currentCoroutineContext().ensureActive()
        dao.finishResearch(id, requestId, "ready", result.text, AiContract.citationsJson(result.citations), "", System.currentTimeMillis())
    }
}

class AiWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val stage = inputData.getString("stage") ?: return Result.failure()
        val requestId = inputData.getString("requestId").orEmpty()
        val dao = HandshakeDatabase.get(applicationContext).dao()
        val runner = AiJobRunner(dao, PhotoStorage(applicationContext), OpenAiClient(AiSettings(applicationContext)), MemoAudio(applicationContext))
        return try {
            when (stage) {
                "extract" -> runner.extract(id)
                "research" -> runner.research(id, requestId)
                "summary" -> runner.summarize(id, requestId)
                "transcription" -> runner.transcribe(id, requestId)
                else -> return Result.failure()
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            val retry = e is AiFailure && e.retryable && runAttemptCount < 2
            val message = (e as? AiFailure)?.message ?: "Processing failed. Your saved data is safe; please retry."
            val state = if (retry) "queued" else "failed"
            if (stage == "extract") dao.capture(id)?.let {
                if (it.state in listOf("queued", "running")) dao.updateCapture(it.copy(state = state, error = message.orEmpty()))
            } else if (stage in listOf("summary", "transcription")) dao.summary(id)?.let {
                dao.finishSummary(id, requestId, if (stage == "transcription") "transcription_$state" else state, it.result, message.orEmpty(), it.analyzedAt)
            } else dao.researchFor(id)?.let {
                dao.finishResearch(id, requestId, state, it.summary, it.citations, message.orEmpty(), it.researchedAt)
            }
            if (retry) Result.retry() else Result.failure()
        }
    }
}

class AiJobs(private val context: Context) {
    private val manager get() = WorkManager.getInstance(context)
    suspend fun enqueue(stage: String, id: String, requestId: String = "") = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<AiWorker>()
            .setInputData(workDataOf("stage" to stage, "id" to id, "requestId" to requestId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("handshake-ai")
            .build()
        manager.enqueueUniqueWork("$stage-$id", ExistingWorkPolicy.KEEP, request).result.get()
        Unit
    }
    suspend fun cancel(stage: String, id: String) = withContext(Dispatchers.IO) {
        manager.cancelUniqueWork("$stage-$id").result.get()
        Unit
    }
    suspend fun recover(dao: HandshakeDao) {
        dao.summaries().first().filter { it.state in listOf("transcription_queued", "transcription_running") }.forEach { enqueue("transcription", it.encounterId, it.requestId) }
        dao.summaries().first().filter { it.state in listOf("queued", "running") }.forEach { enqueue("summary", it.encounterId, it.requestId) }
        dao.captures().first().filter { it.state in listOf("queued", "running") }.forEach { enqueue("extract", it.id) }
        dao.research().first().filter { it.state in listOf("queued", "running") }.forEach { enqueue("research", it.encounterId, it.requestId) }
    }
}
