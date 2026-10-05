package dev.pam.backgroundtransfer

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONObject

/** A step failure; [retryable] network/server errors are retried with backoff. */
internal class TransferFailure(message: String, val retryable: Boolean, val statusCode: Int = 0, val body: String = "", cause: Throwable? = null) :
    Exception(message, cause)

internal data class StepResult(val statusCode: Int, val body: String)

/** Resolved local file with the MIME type and name that will be sent. */
internal data class LocalFile(val file: File, val mimeType: String, val filename: String)

/**
 * Executes one [StepSpec] with OkHttp. Request bodies are streamed from disk
 * with byte progress; responses are bounded or streamed into a sandbox file.
 */
internal class TransferHttp(
    private val client: OkHttpClient = shared,
    private val secrets: (String) -> String?,
    private val files: (String, String, String) -> LocalFile,
    private val destination: (String) -> File,
) {
    suspend fun execute(
        step: StepSpec,
        templates: TransferTemplates,
        progress: (Long) -> Unit,
        cancelled: () -> Boolean,
    ): StepResult {
        val request = try {
            build(step, templates, progress, cancelled)
        } catch (error: TemplateException) {
            throw TransferFailure(error.message ?: "Template failure", retryable = false, cause = error)
        }
        val call = client.newCall(request)
        val result = try {
            call.await { response -> read(step, response, progress, cancelled) }
        } catch (error: TransferFailure) {
            throw error
        } catch (error: IOException) {
            throw TransferFailure(error.message ?: "Network failure", retryable = !cancelled(), cause = error)
        }
        if (result.statusCode !in 200..299) {
            val retryable = result.statusCode == 408 || result.statusCode == 425 || result.statusCode == 429 || result.statusCode >= 500
            throw TransferFailure("HTTP ${result.statusCode}", retryable, result.statusCode, result.body)
        }
        return result
    }

    /** Sum of the bytes this step will stream from disk. */
    fun uploadSize(step: StepSpec): Long = when (val body = step.body) {
        is BodySpec.File -> files(body.path, body.mimeType, body.path.substringAfterLast('/')).file.length()
        is BodySpec.Multipart -> body.parts.filterIsInstance<PartSpec.File>().sumOf { files(it.path, it.mimeType, it.filename).file.length() }
        else -> 0L
    }

    private fun build(step: StepSpec, templates: TransferTemplates, progress: (Long) -> Unit, cancelled: () -> Boolean): Request {
        val url = templates.string(step.url)
        try {
            TransferUrls.requireAllowed(url)
        } catch (error: IllegalArgumentException) {
            throw TransferFailure("Resolved URL is not allowed", retryable = false, cause = error)
        }
        val headers = Headers.Builder()
        step.headersFrom?.let { path ->
            val source = templates.lookup(path) as? JSONObject ?: throw TemplateException("{{$path}} is not an object")
            source.keys().forEach { name ->
                val value = source.opt(name)
                if (value is String && !name.equals("Content-Length", true) && !name.equals("Host", true)) headers.set(name, value)
            }
        }
        step.headers.forEach { header ->
            val value = when {
                header.secretVault != null -> secrets(header.secretVault)
                    ?: throw TransferFailure("Secret \"${header.secretVault}\" is not stored", retryable = false)
                header.secretValue != null -> header.secretValue
                else -> templates.string(header.value.orEmpty())
            }
            headers.set(header.name, header.prefix + value)
        }
        val counter: (Long) -> Unit = progress
        val body: RequestBody? = when (val spec = step.body) {
            null -> if (step.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody(null) else null
            is BodySpec.Json -> templates.json(spec.value).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            is BodySpec.Form -> FormBody.Builder().apply {
                spec.fields.keys().forEach { name -> add(name, templates.string(spec.fields.get(name).toString())) }
            }.build()
            is BodySpec.File -> files(spec.path, spec.mimeType, spec.path.substringAfterLast('/')).let { local ->
                FileBody(local.file, local.mimeType.toMediaType(), counter, cancelled)
            }
            is BodySpec.Multipart -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                spec.parts.forEach { part ->
                    when (part) {
                        is PartSpec.Field -> addFormDataPart(part.name, templates.string(part.value))
                        is PartSpec.File -> files(part.path, part.mimeType, part.filename).let { local ->
                            addFormDataPart(part.name, local.filename, FileBody(local.file, local.mimeType.toMediaType(), counter, cancelled))
                        }
                    }
                }
            }.build()
        }
        return Request.Builder().url(url).headers(headers.build()).method(step.method, body).build()
    }

    private fun read(step: StepSpec, response: Response, progress: (Long) -> Unit, cancelled: () -> Boolean): StepResult {
        val body = response.body ?: return StepResult(response.code, "")
        val target = step.saveTo
        if (target == null || !response.isSuccessful) {
            return StepResult(response.code, readBounded(body.source().inputStream()))
        }
        val output = destination(target)
        output.parentFile?.mkdirs()
        val partial = File(output.parentFile, ".${output.name}.part")
        body.source().use { source ->
            partial.sink().buffer().use { sink ->
                val buffer = okio.Buffer()
                while (true) {
                    if (cancelled()) throw IOException("Transfer cancelled")
                    val count = source.read(buffer, CHUNK)
                    if (count < 0) break
                    sink.write(buffer, count)
                    progress(count)
                }
            }
        }
        if (!partial.renameTo(output)) {
            output.delete()
            check(partial.renameTo(output)) { "Unable to move the downloaded file into place" }
        }
        return StepResult(response.code, JSONObject().put("path", target).put("bytes", output.length()).toString())
    }

    private fun readBounded(input: java.io.InputStream): String = input.use { stream ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (bytes.size() < MAX_RESPONSE_BYTES) {
            val count = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - bytes.size()))
            if (count < 0) break
            bytes.write(buffer, 0, count)
        }
        bytes.toString(Charsets.UTF_8.name())
    }

    /** Streams a file, reporting every chunk; restarts cleanly when OkHttp retries the body. */
    private class FileBody(
        private val file: File,
        private val type: MediaType,
        private val progress: (Long) -> Unit,
        private val cancelled: () -> Boolean,
    ) : RequestBody() {
        private var reported = 0L

        override fun contentType() = type

        override fun contentLength() = file.length()

        override fun writeTo(sink: BufferedSink) {
            if (reported > 0) {
                progress(-reported)
                reported = 0
            }
            file.source().use { source ->
                val buffer = okio.Buffer()
                while (true) {
                    if (cancelled()) throw IOException("Transfer cancelled")
                    val count = source.read(buffer, CHUNK)
                    if (count < 0) break
                    sink.write(buffer, count)
                    reported += count
                    progress(count)
                }
            }
        }
    }

    companion object {
        private const val CHUNK = 64L * 1024
        const val MAX_RESPONSE_BYTES = 1024 * 1024

        val shared: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

private suspend fun <T> Call.await(handle: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(handle) }
            if (!continuation.isActive) return
            result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    })
}
