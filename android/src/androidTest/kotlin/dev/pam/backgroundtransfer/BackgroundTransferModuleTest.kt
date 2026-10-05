package dev.pam.backgroundtransfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundTransferModuleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.filesDir, "pam-files")
    private lateinit var server: MockWebServer
    private lateinit var module: BackgroundTransferModule
    private val requests = mutableListOf<RecordedRequest>()
    private var responder: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before
    fun setUp() {
        File(root, "bt-test").deleteRecursively()
        File(root, "bt-test").mkdirs()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return responder(request)
            }
        }
        server.start()
        module = BackgroundTransferModule(context)
        call("secretPut", mapOf("name" to WireValue.Text("session"), "value" to WireValue.Text("vault-token-1")))
    }

    @After
    fun tearDown() {
        module.close()
        runCatching { server.shutdown() } // a delayed response may still be parked after a cancel
        File(root, "bt-test").deleteRecursively()
    }

    @Test
    fun multipartUploadThenChainedRequestWithVaultSecretAndWatch() {
        val file = write("bt-test/photo.jpg", 300_000)
        responder = { request ->
            when (request.path) {
                "/media" -> MockResponse().setResponseCode(201).setBody("""{"id":42,"url":"https://cdn/x"}""")
                "/messages" -> MockResponse().setResponseCode(201).setBody("""{"message":{"id":7}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val spec = spec(
            kind = 2,
            steps = JSONArray()
                .put(
                    step(2, url("/media"))
                        .put("headers", JSONArray().put(bearerVault()).put(JSONObject().put("name", "Idempotency-Key").put("value", "key-{{transfer.id}}")))
                        .put(
                            "body",
                            JSONObject().put("type", "multipart").put(
                                "parts",
                                JSONArray()
                                    .put(JSONObject().put("type", "file").put("name", "file").put("path", "bt-test/photo.jpg").put("mimeType", "image/jpeg").put("filename", "photo.jpg"))
                                    .put(JSONObject().put("type", "field").put("name", "caption").put("value", "Olá {{transfer.tag}}")),
                            ),
                        ),
                )
                .put(step(2, url("/messages")).put("headers", JSONArray().put(bearerVault())).put("body", JSONObject().put("type", "json").put("value", JSONObject().put("media", "{{response.id}}").put("chat", 42)))),
        )
        val id = enqueue(spec)
        val subscription = call("watch", mapOf("identifier" to WireValue.Text(id))).int("subscription")
        val seen = mutableListOf<Map<String, WireValue>>()
        do {
            val snapshot = call("watchNext", mapOf("subscription" to WireValue.Integer(subscription)), 60)
            seen += snapshot
        } while (snapshot.int("state") !in setOf(3L, 4L, 5L))
        call("unwatch", mapOf("subscription" to WireValue.Integer(subscription)))
        val final = seen.last()
        assertEquals(final.toString(), 3L, final.int("state"))
        assertEquals(201L, final.int("statusCode"))
        assertEquals("""{"message":{"id":7}}""", final.text("responseBody"))
        assertEquals(file.length(), final.int("bytesTotal"))
        assertEquals("chat:42", final.text("tag"))
        val upload = requests.first { it.path == "/media" }
        assertEquals("Bearer vault-token-1", upload.getHeader("Authorization"))
        assertEquals("key-$id", upload.getHeader("Idempotency-Key"))
        assertTrue(upload.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = upload.body.readUtf8()
        assertTrue(body.contains("filename=\"photo.jpg\"") && body.contains("Olá chat:42"))
        assertTrue(upload.bodySize > file.length())
        val message = JSONObject(requests.first { it.path == "/messages" }.body.readUtf8())
        assertEquals(42, message.get("media"))
        assertFalse("encrypted payload removed after success", TransferPaths.workDirectory(context, id).exists())
    }

    @Test
    fun signedUrlFlowUsesBeforeStepResponseAndHeaders() {
        write("bt-test/clip.bin", 64_000)
        responder = { request ->
            when (request.path) {
                "/sign" -> MockResponse().setBody(
                    JSONObject().put("data", JSONObject().put("upload_url", url("/bucket/k1?sig=abc")).put("key", "k1").put("headers", JSONObject().put("x-amz-acl", "private"))).toString(),
                )
                "/bucket/k1?sig=abc" -> MockResponse().setResponseCode(200)
                "/posts" -> MockResponse().setResponseCode(201).setBody("""{"ok":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val id = enqueue(
            spec(
                kind = 2,
                steps = JSONArray()
                    .put(step(2, url("/sign")).put("name", "sign").put("body", JSONObject().put("type", "json").put("value", JSONObject().put("file_name", "clip.bin"))))
                    .put(step(3, "{{steps.sign.data.upload_url}}").put("headersFrom", "steps.sign.data.headers").put("body", JSONObject().put("type", "file").put("path", "bt-test/clip.bin").put("mimeType", "application/octet-stream")))
                    .put(step(2, url("/posts")).put("body", JSONObject().put("type", "json").put("value", JSONObject().put("media_items", JSONArray().put(JSONObject().put("key", "{{steps.sign.data.key}}")))))),
            ),
        )
        val final = await(id)
        assertEquals(final.toString(), 3L, final.int("state"))
        val put = requests.first { it.method == "PUT" }
        assertEquals("private", put.getHeader("x-amz-acl"))
        assertEquals(64_000L, put.bodySize)
        assertEquals("k1", JSONObject(requests.first { it.path == "/posts" }.body.readUtf8()).getJSONArray("media_items").getJSONObject(0).getString("key"))
    }

    @Test
    fun clientErrorsFailImmediatelyAndRetryResumesAfterCompletedSteps() {
        write("bt-test/a.bin", 10_000)
        var accept = false
        responder = { request ->
            when (request.path) {
                "/upload" -> MockResponse().setResponseCode(201).setBody("""{"id":"u1"}""")
                "/finish" -> if (accept) MockResponse().setResponseCode(200).setBody("done") else MockResponse().setResponseCode(422).setBody("""{"error":"invalid"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val id = enqueue(
            spec(
                kind = 2,
                steps = JSONArray()
                    .put(step(2, url("/upload")).put("body", JSONObject().put("type", "file").put("path", "bt-test/a.bin").put("mimeType", "application/octet-stream")))
                    .put(step(2, url("/finish")).put("body", JSONObject().put("type", "json").put("value", JSONObject().put("id", "{{response.id}}")))),
            ),
        )
        val failed = await(id)
        assertEquals(4L, failed.int("state"))
        assertEquals(422L, failed.int("statusCode"))
        assertEquals(1L, failed.int("attempt"))
        accept = true
        call("retry", mapOf("identifier" to WireValue.Text(id)))
        val done = await(id)
        assertEquals(done.toString(), 3L, done.int("state"))
        assertEquals("upload step is never repeated", 1, requests.count { it.path == "/upload" })
        assertEquals(2, requests.count { it.path == "/finish" })
        assertEquals("u1", JSONObject(requests.last { it.path == "/finish" }.body.readUtf8()).getString("id"))
    }

    @Test
    fun serverErrorsAreRetriedWithBackoff() {
        var calls = 0
        responder = { request ->
            if (request.path == "/flaky") {
                calls++
                if (calls == 1) MockResponse().setResponseCode(503) else MockResponse().setResponseCode(204)
            } else {
                MockResponse().setResponseCode(404)
            }
        }
        val id = enqueue(spec(kind = 3, steps = JSONArray().put(step(2, url("/flaky")))).put("retry", JSONObject().put("times", 2).put("backoff", 1).put("delaySeconds", 10)))
        val done = await(id, 90)
        assertEquals(done.toString(), 3L, done.int("state"))
        assertEquals(2L, done.int("attempt"))
        assertEquals(2, calls)
    }

    @Test
    fun cancelStopsARunningTransfer() {
        responder = { MockResponse().setHeadersDelay(20, TimeUnit.SECONDS).setResponseCode(200) }
        val id = enqueue(spec(kind = 3, steps = JSONArray().put(step(1, url("/slow")))))
        val deadline = System.currentTimeMillis() + 20_000
        while (requests.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        call("cancel", mapOf("identifier" to WireValue.Text(id)))
        val cancelled = await(id, 10)
        assertEquals(5L, cancelled.int("state"))
        Thread.sleep(1_000)
        assertEquals(5L, call("status", mapOf("identifier" to WireValue.Text(id))).int("state"))
    }

    @Test
    fun downloadsIntoTheSandboxAtomically() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        responder = { MockResponse().setBody(Buffer().write(payload)) }
        val id = enqueue(spec(kind = 1, steps = JSONArray().put(step(1, url("/file")).put("saveTo", "bt-test/downloads/file.bin"))))
        val done = await(id)
        assertEquals(done.toString(), 3L, done.int("state"))
        val file = File(root, "bt-test/downloads/file.bin")
        assertTrue(file.readBytes().contentEquals(payload))
        assertFalse(File(root, "bt-test/downloads/.file.bin.part").exists())
    }

    @Test
    fun transcodesVideoPartsThroughPamNativeMediaBeforeUploading() {
        val source = File(root, "bt-test/clip.mov").apply {
            InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { input -> outputStream().use(input::copyTo) }
        }
        responder = { MockResponse().setResponseCode(201).setBody("{}") }
        val id = enqueue(
            spec(
                kind = 2,
                steps = JSONArray().put(
                    step(2, url("/video")).put(
                        "body",
                        JSONObject().put("type", "multipart").put(
                            "parts",
                            JSONArray().put(JSONObject().put("type", "file").put("name", "file").put("path", "bt-test/clip.mov").put("mimeType", "video/quicktime").put("filename", "clip.mov")),
                        ),
                    ),
                ),
            ).put("transcode", JSONObject().put("preset", 1).put("maxBitrate", 0).put("fastStart", true)),
        )
        val done = await(id, 120)
        assertEquals(done.toString(), 3L, done.int("state"))
        val body = requests.single().body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("filename=\"clip.mp4\"") && text.contains("Content-Type: video/mp4"))
        assertNotEquals(source.length(), body.size.toLong())
        assertTrue("fast-start output: moov precedes mdat", text.indexOf("moov") in 0 until text.indexOf("mdat"))
    }

    @Test
    fun secretsAndBodiesAreEncryptedAtRestAndUniqueKeysDeduplicate() {
        responder = { MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setResponseCode(204) }
        val spec = spec(kind = 3, steps = JSONArray().put(step(2, url("/x")).put("headers", JSONArray().put(JSONObject().put("name", "Authorization").put("prefix", "Bearer ").put("secret", JSONObject().put("value", "literal-secret-123"))))))
            .put("unique", "dedupe-1")
        val first = enqueue(spec)
        val second = enqueue(spec)
        assertEquals(first, second)
        val sealed = File(TransferPaths.workDirectory(context, first), "spec.enc").readText()
        assertFalse(sealed.contains("literal-secret-123"))
        val prefs = File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().joinToString { it.readText() }
        assertFalse(prefs.contains("literal-secret-123") || prefs.contains("vault-token-1"))
        call("cancel", mapOf("identifier" to WireValue.Text(first)))
        val listed = JSONArray(call("list", emptyMap()).text("transfers"))
        assertTrue((0 until listed.length()).any { listed.getJSONObject(it).getString("identifier") == first })
        assertTrue(call("prune", mapOf("olderThanDays" to WireValue.Integer(0))).int("removed") >= 1)
        assertEquals(ModuleResultStatus.FAILURE, raw("status", mapOf("identifier" to WireValue.Text(first))).first)
    }

    @Test
    fun rejectsMissingFilesAndRemoteHttp() {
        val missing = spec(kind = 2, steps = JSONArray().put(step(2, url("/x")).put("body", JSONObject().put("type", "file").put("path", "bt-test/nope.bin").put("mimeType", "application/octet-stream"))))
        assertEquals(ModuleResultStatus.FAILURE, raw("enqueue", mapOf("spec" to WireValue.Text(missing.toString()))).first)
        val insecure = spec(kind = 3, steps = JSONArray().put(step(1, "http://example.com/x")))
        assertEquals(ModuleResultStatus.FAILURE, raw("enqueue", mapOf("spec" to WireValue.Text(insecure.toString()))).first)
    }

    private fun url(path: String) = server.url(path).toString().replace("://localhost:", "://127.0.0.1:")

    private fun spec(kind: Int, steps: JSONArray) = JSONObject()
        .put("version", 1).put("kind", kind).put("tag", "chat:42").put("network", 1)
        .put("retry", JSONObject().put("times", 0).put("backoff", 2).put("delaySeconds", 10))
        .put("steps", steps)

    private fun step(method: Int, url: String) = JSONObject().put("method", method).put("url", url)

    private fun bearerVault() = JSONObject().put("name", "Authorization").put("prefix", "Bearer ").put("secret", JSONObject().put("vault", "session"))

    private fun write(path: String, size: Int) = File(root, path).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(size) { (it % 199).toByte() })
    }

    private fun enqueue(spec: JSONObject): String = call("enqueue", mapOf("spec" to WireValue.Text(spec.toString()))).text("identifier")

    private fun await(id: String, seconds: Long = 45): Map<String, WireValue> {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (true) {
            val snapshot = call("status", mapOf("identifier" to WireValue.Text(id)))
            if (snapshot.int("state") in setOf(3L, 4L, 5L) || System.currentTimeMillis() > deadline) return snapshot
            Thread.sleep(200)
        }
    }

    private fun raw(method: String, values: Map<String, WireValue>, seconds: Long = 30): Pair<ModuleResultStatus, ByteArray> {
        val latch = CountDownLatch(1)
        var result: Pair<ModuleResultStatus, ByteArray> = ModuleResultStatus.FAILURE to ByteArray(0)
        module.invoke(method, WireMap.encode(values)) { status, payload ->
            result = status to payload
            latch.countDown()
        }
        assertTrue("$method timed out", latch.await(seconds, TimeUnit.SECONDS))
        return result
    }

    private fun call(method: String, values: Map<String, WireValue>, seconds: Long = 30): Map<String, WireValue> {
        val (status, payload) = raw(method, values, seconds)
        assertEquals("$method failed: ${String(payload)}", ModuleResultStatus.SUCCESS, status)
        return WireMap.decode(payload)
    }

    private fun Map<String, WireValue>.int(key: String) = (this[key] as WireValue.Integer).value

    private fun Map<String, WireValue>.text(key: String) = (this[key] as WireValue.Text).value
}
