package dev.pam.backgroundtransfer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSpecTest {
    private val spec = """
        {"version":1,"kind":2,"tag":"chat:42","unique":"m:1","network":2,
         "retry":{"times":5,"backoff":1,"delaySeconds":60},
         "notification":{"title":"Enviando","progress":true},
         "transcode":{"preset":2,"maxBitrate":0,"fastStart":true},
         "steps":[
          {"name":"upload","method":2,"url":"https://api.example.test/media",
           "headers":[{"name":"Authorization","prefix":"Bearer ","secret":{"vault":"session"}}],
           "body":{"type":"multipart","parts":[
             {"type":"file","name":"file","path":"captures/a.mov","mimeType":"video/quicktime","filename":"a.mov"},
             {"type":"field","name":"caption","value":"{{transfer.tag}}"}]}},
          {"method":2,"url":"https://api.example.test/messages","body":{"type":"json","value":{"media":"{{response.id}}"}}}
         ]}
    """.trimIndent()

    @Test
    fun parsesTheWireContract() {
        val parsed = TransferSpec.parse(spec)
        assertEquals(2, parsed.kind)
        assertEquals("chat:42", parsed.tag)
        assertEquals(RetryPolicy(5, 1, 60), parsed.retry)
        assertEquals(listOf("captures/a.mov"), parsed.files())
        assertEquals(listOf("captures/a.mov"), parsed.videoFiles())
        assertEquals("session", parsed.steps[0].headers.single().secretVault)
        assertEquals("POST", parsed.steps[1].method)
        assertTrue(parsed.steps[1].body is BodySpec.Json)
        assertTrue(parsed.steps[0].retryable)
        val once = TransferSpec.parse(spec.replace("\"url\":\"https://api.example.test/messages\"", "\"url\":\"https://api.example.test/messages\",\"retry\":false"))
        assertTrue(!once.steps[1].retryable)
        val caption = (parsed.steps[0].body as BodySpec.Multipart).parts.filterIsInstance<PartSpec.Field>().single()
        assertTrue(!caption.literal)
        val literal = TransferSpec.parse(spec.replace("\"value\":\"{{transfer.tag}}\"", "\"value\":\"{{transfer.tag}}\",\"literal\":true"))
        assertTrue((literal.steps[0].body as BodySpec.Multipart).parts.filterIsInstance<PartSpec.Field>().single().literal)
    }

    @Test
    fun rejectsUnsafeDescriptions() {
        val unsafe = listOf(
            spec.replace("https://api.example.test/media", "http://evil.example.test/media"),
            spec.replace("captures/a.mov", "../a.mov"),
            spec.replace("\"method\":2,\"url\":\"https://api.example.test/messages\"", "\"method\":1,\"url\":\"https://api.example.test/messages\""),
            spec.replace("\"name\":\"Authorization\"", "\"name\":\"Bad Header\""),
            spec.replace("\"kind\":2", "\"kind\":9"),
        )
        unsafe.forEachIndexed { index, json ->
            val accepted = runCatching { TransferSpec.parse(json) }.isSuccess
            assertTrue("unsafe case $index accepted", !accepted)
        }
        TransferSpec.parse(spec.replace("https://api.example.test/media", "http://127.0.0.1:8080/media"))
    }

    @Test
    fun resolvesTemplatesKeepingJsonTypes() {
        val templates = TransferTemplates(
            transferId = "id-12345678",
            tag = "chat:42",
            last = TransferTemplates.parse("""{"id":42,"data":{"items":[{"key":"k1"}]}}"""),
            named = mapOf("sign" to TransferTemplates.parse("""{"data":{"upload_url":"https://s3.example.test/x?sig=1","headers":{"x-acl":"private"}}}""")),
        )
        assertEquals("https://s3.example.test/x?sig=1", templates.string("{{steps.sign.data.upload_url}}"))
        assertEquals("media 42 for chat:42", templates.string("media {{ response.id }} for {{transfer.tag}}"))
        val resolved = templates.json(JSONObject().put("media", "{{response.id}}").put("items", JSONArray().put("{{response.data.items.0.key}}"))) as JSONObject
        assertEquals(42, resolved.get("media"))
        assertEquals("k1", resolved.getJSONArray("items").getString(0))
        assertTrue(templates.lookup("steps.sign.data.headers") is JSONObject)
        assertTrue(runCatching { templates.string("{{response.missing}}") }.exceptionOrNull() is TemplateException)
        assertTrue(runCatching { templates.string("{{steps.other.id}}") }.exceptionOrNull() is TemplateException)
    }

    @Test
    fun checkpointsRoundTrip() {
        val progress = TransferProgress(2, "{\"id\":1}", mapOf("sign" to "{}"), mapOf("a.mov" to "/x/transcoded-1.mp4"))
        assertEquals(progress, TransferProgress.fromJson(progress.toJson()))
        assertEquals(1, progress.templates("id-12345678", null).lookup("response.id"))
    }

    private val signedChain = """
        {"version":1,"kind":2,
         "notification":{"title":"Zé Chat","failed":"Não foi possível enviar","failedText":"Toque para tentar de novo"},
         "steps":[
          {"name":"sign0","method":2,"url":"https://api.example.test/sign","body":{"type":"json","value":{"n":0}}},
          {"method":3,"url":"{{steps.sign0.data.upload_url}}","headersFrom":"steps.sign0.data.headers",
           "body":{"type":"file","path":"media/a.jpg","mimeType":"image/jpeg"}},
          {"name":"sign1","method":2,"url":"https://api.example.test/sign","body":{"type":"json","value":{"n":1}}},
          {"method":3,"url":"{{steps.sign1.data.upload_url}}","body":{"type":"file","path":"media/b.jpg","mimeType":"image/jpeg"}},
          {"method":2,"url":"https://api.example.test/posts","body":{"type":"json","value":{"key":"{{steps.sign1.data.key}}"}}},
          {"method":2,"url":"https://api.example.test/after","body":{"type":"json","value":{"id":"{{response.id}}"}}},
          {"method":3,"url":"{{response.next}}"}
         ]}
    """.trimIndent()

    @Test
    fun anExpiredSignedUrlRewindsOnceToTheStepThatSignedIt() {
        val steps = TransferSpec.parse(signedChain).steps
        // A resumed PUT (403/401) re-runs its sign step, once per run.
        assertEquals(0, TransferRefresh.rewindTo(steps, failed = 1, statusCode = 403, refreshed = emptySet()))
        assertEquals(2, TransferRefresh.rewindTo(steps, failed = 3, statusCode = 401, refreshed = emptySet()))
        assertEquals(null, TransferRefresh.rewindTo(steps, failed = 3, statusCode = 403, refreshed = setOf(2)))
        // Other statuses, body-only references and steps without producers fail as before.
        assertEquals(2, TransferRefresh.rewindTo(steps, failed = 3, statusCode = 400, refreshed = emptySet()))
        assertEquals(null, TransferRefresh.rewindTo(steps, failed = 3, statusCode = 404, refreshed = emptySet()))
        assertEquals(null, TransferRefresh.rewindTo(steps, failed = 4, statusCode = 403, refreshed = emptySet()))
        assertEquals(null, TransferRefresh.rewindTo(steps, failed = 0, statusCode = 403, refreshed = emptySet()))
        // {{response}} names the previous step, which replays only if it does not read {{response}} itself.
        assertEquals(null, TransferRefresh.rewindTo(steps, failed = 6, statusCode = 403, refreshed = emptySet()))
    }

    @Test
    fun theFailureNotificationShowsOnlyTheAppText() {
        val notification = TransferSpec.parse(signedChain).notification!!
        assertEquals("Não foi possível enviar", notification.failed)
        assertEquals("Toque para tentar de novo", notification.resultText(success = false))
        assertEquals(null, notification.resultText(success = true))
        assertEquals(null, notification.copy(failedText = null).resultText(success = false))
    }

    @Test
    fun literalJsonPathsAreSentVerbatim() {
        val step = TransferSpec.parse(
            """{"version":1,"kind":3,"steps":[{"method":2,"url":"https://api.example.test/posts",
              "body":{"type":"json","value":{"caption":"{{response.id}} oi","key":"{{response.id}}",
                "items":[{"alt":"{{x}}","key":"{{response.id}}"},{"alt":"{{steps.y}}"}]},
              "literal":["caption","items.*.alt"]}}]}""",
        ).steps.single()
        val body = step.body as BodySpec.Json
        assertEquals(listOf("caption", "items.*.alt"), body.literal)
        val templates = TransferTemplates("id-12345678", null, JSONObject().put("id", 7), emptyMap())
        val resolved = templates.json(body.value, body.literal) as JSONObject
        assertEquals("{{response.id}} oi", resolved.getString("caption"))
        assertEquals(7, resolved.get("key"))
        val items = resolved.getJSONArray("items")
        assertEquals("{{x}}", items.getJSONObject(0).getString("alt"))
        assertEquals(7, items.getJSONObject(0).get("key"))
        assertEquals("{{steps.y}}", items.getJSONObject(1).getString("alt"))
        assertTrue(runCatching {
            TransferSpec.parse("""{"version":1,"kind":3,"steps":[{"method":2,"url":"https://a.test/x","body":{"type":"json","value":{},"literal":["a..b"]}}]}""")
        }.isFailure)
    }

    @Test
    fun theResultChannelHasItsOwnLabel() {
        val spec = NotificationSpec("Zé Chat", null, false, "Envios", null, "Falhou")
        assertEquals("Envios", spec.resultChannelName())
        assertEquals("Resultados de envio", spec.copy(resultChannel = "Resultados de envio").resultChannelName())
        assertEquals("Transfers", spec.copy(channel = null).resultChannelName())
    }
}
