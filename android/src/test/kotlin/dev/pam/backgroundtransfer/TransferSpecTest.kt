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
}
