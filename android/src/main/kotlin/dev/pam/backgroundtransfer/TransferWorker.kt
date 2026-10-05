package dev.pam.backgroundtransfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes a persisted transfer: optional video transcode, then every step in
 * order. Completed steps and their responses are checkpointed (encrypted), so
 * retries and process restarts resume after the last successful step.
 */
class TransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val store = TransferStore.get(context)
    private val vault = SecretVault(context)
    @Volatile private var lastForeground = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_ID) ?: error("Transfer identifier missing")
        val spec = TransferFiles.readSpec(applicationContext, id)
        val record = store.get(id)
        return TransferNotifications.foreground(
            applicationContext,
            id,
            spec.kind,
            spec.notification ?: NotificationSpec("Transfer", null, true, null, null, null),
            record?.transferred ?: 0,
            record?.total ?: 0,
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val record = store.get(id) ?: return@withContext Result.failure()
        if (TransferStates.finished(record.state)) return@withContext Result.failure()
        val spec = runCatching { TransferFiles.readSpec(applicationContext, id) }.getOrElse {
            store.update(id) { it.copy(state = TransferStates.FAILED, message = "Transfer payload is unavailable") }
            return@withContext Result.failure()
        }
        store.update(id) {
            it.copy(
                state = if (runAttemptCount == 0) TransferStates.RUNNING else TransferStates.RETRYING,
                attempt = runAttemptCount + 1,
                message = "",
            )
        }
        spec.notification?.let { notification ->
            runCatching { setForeground(TransferNotifications.foreground(applicationContext, id, spec.kind, notification, 0, 0)) }
        }
        try {
            val result = run(id, spec)
            store.update(id) {
                it.copy(
                    state = TransferStates.SUCCEEDED,
                    stage = TransferStages.DONE,
                    step = spec.steps.size,
                    transferred = maxOf(it.transferred, it.total),
                    statusCode = result.statusCode,
                    responseBody = result.body.take(TransferRecord.MAX_RESPONSE_CHARS),
                    message = "",
                )
            }
            spec.notification?.let { TransferNotifications.result(applicationContext, id, it, true, "") }
            TransferFiles.delete(applicationContext, id)
            Result.success()
        } catch (error: CancellationException) {
            store.update(id) { current ->
                if (current.state == TransferStates.CANCELLED) current
                else current.copy(state = TransferStates.QUEUED, stage = TransferStages.WAITING, message = "Waiting to resume")
            }
            throw error
        } catch (error: Throwable) {
            val failure = error as? TransferFailure ?: TransferFailure(error.message ?: "Transfer failed", retryable = false, cause = error)
            if (store.get(id)?.state == TransferStates.CANCELLED) return@withContext Result.failure()
            if (failure.retryable && runAttemptCount < spec.retry.times) {
                store.update(id) {
                    it.copy(
                        state = TransferStates.RETRYING,
                        stage = TransferStages.WAITING,
                        message = failure.message.orEmpty(),
                        statusCode = failure.statusCode,
                        responseBody = failure.body.take(TransferRecord.MAX_RESPONSE_CHARS),
                    )
                }
                Result.retry()
            } else {
                store.update(id) {
                    it.copy(
                        state = TransferStates.FAILED,
                        message = failure.message.orEmpty(),
                        statusCode = failure.statusCode,
                        responseBody = failure.body.take(TransferRecord.MAX_RESPONSE_CHARS),
                    )
                }
                spec.notification?.let { TransferNotifications.result(applicationContext, id, it, false, failure.message.orEmpty()) }
                Result.failure()
            }
        }
    }

    private suspend fun run(id: String, spec: TransferSpec): StepResult {
        var progress = TransferFiles.readProgress(applicationContext, id)
        progress = transcode(id, spec, progress)
        val transcoded = progress.transcoded
        val http = TransferHttp(
            secrets = vault::get,
            files = { path, mime, filename ->
                transcoded[path]?.let { output ->
                    LocalFile(File(output), "video/mp4", filename.substringBeforeLast('.') + ".mp4")
                } ?: LocalFile(TransferPaths.resolve(applicationContext, path, mustExist = true), mime, filename)
            },
            destination = { path -> TransferPaths.resolve(applicationContext, path, mustExist = false) },
        )
        val total = spec.steps.drop(progress.next).sumOf { runCatching { http.uploadSize(it) }.getOrDefault(0L) }
        var sent = 0L
        store.progress(id, 0, total)
        var last = StepResult(0, progress.last.orEmpty())
        for (index in progress.next until spec.steps.size) {
            val step = spec.steps[index]
            val stage = when {
                step.saveTo != null -> TransferStages.DOWNLOADING
                step.body is BodySpec.File || step.body is BodySpec.Multipart -> TransferStages.UPLOADING
                else -> TransferStages.REQUESTING
            }
            store.update(id) { it.copy(stage = stage, step = index + 1, steps = spec.steps.size) }
            val stepStart = sent
            last = try {
                http.execute(
                    step,
                    progress.templates(id, spec.tag),
                    progress = { delta ->
                        sent += delta
                        val bytesTotal = if (stage == TransferStages.DOWNLOADING) 0 else total
                        store.progress(id, sent, bytesTotal)
                        updateNotification(id, spec, sent, bytesTotal)
                    },
                    cancelled = { isStopped },
                )
            } catch (error: TransferFailure) {
                sent = stepStart
                throw error
            }
            progress = progress.copy(
                next = index + 1,
                last = last.body,
                named = step.name?.let { progress.named + (it to last.body) } ?: progress.named,
            )
            TransferFiles.writeProgress(applicationContext, id, progress)
        }
        return last
    }

    private fun transcode(id: String, spec: TransferSpec, initial: TransferProgress): TransferProgress {
        val options = spec.transcode ?: return initial
        var progress = initial
        val pending = spec.videoFiles().filter { path -> progress.transcoded[path]?.let { File(it).isFile } != true }
        if (pending.isEmpty()) return progress
        store.update(id) { it.copy(stage = TransferStages.TRANSCODING) }
        val directory = TransferPaths.workDirectory(applicationContext, id).apply { mkdirs() }
        pending.forEachIndexed { index, path ->
            val source = TransferPaths.resolve(applicationContext, path, mustExist = true)
            val output = File(directory, "transcoded-${progress.transcoded.size + 1}.mp4")
            MediaTranscoderBridge.transcode(
                applicationContext,
                source,
                output,
                options,
                cancelled = { isStopped },
                progress = { fraction ->
                    val overall = ((index + fraction.coerceIn(0.0, 1.0)) / pending.size * 1000).toLong()
                    store.progress(id, overall, 1000)
                },
            )
            progress = progress.copy(transcoded = progress.transcoded + (path to output.absolutePath))
            TransferFiles.writeProgress(applicationContext, id, progress)
        }
        return progress
    }

    private fun updateNotification(id: String, spec: TransferSpec, transferred: Long, total: Long) {
        val notification = spec.notification ?: return
        if (!notification.progress) return
        val now = System.currentTimeMillis()
        if (now - lastForeground < 1_000) return
        lastForeground = now
        runCatching { setForegroundAsync(TransferNotifications.foreground(applicationContext, id, spec.kind, notification, transferred, total)) }
    }

    companion object {
        const val KEY_ID = "dev.pam.background-transfer.id"
    }
}
