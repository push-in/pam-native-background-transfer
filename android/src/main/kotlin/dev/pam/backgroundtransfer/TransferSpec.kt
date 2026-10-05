package dev.pam.backgroundtransfer

import org.json.JSONArray
import org.json.JSONObject

/** Parsed, validated transfer description produced by `PendingTransfer::toWire()`. */
internal data class TransferSpec(
    val raw: JSONObject,
    val kind: Int,
    val tag: String?,
    val unique: String?,
    val network: Int,
    val retry: RetryPolicy,
    val notification: NotificationSpec?,
    val transcode: JSONObject?,
    val steps: List<StepSpec>,
) {
    fun files(): List<String> = steps.flatMap { it.body?.files().orEmpty() }

    fun videoFiles(): List<String> = steps.flatMap { step ->
        when (val body = step.body) {
            is BodySpec.File -> if (body.mimeType.startsWith("video/")) listOf(body.path) else emptyList()
            is BodySpec.Multipart -> body.parts.filterIsInstance<PartSpec.File>()
                .filter { it.mimeType.startsWith("video/") }.map { it.path }
            else -> emptyList()
        }
    }.distinct()

    companion object {
        const val MAX_STEPS = 16

        fun parse(json: String): TransferSpec {
            require(json.length <= 4 * 1024 * 1024) { "Transfer description is too large" }
            val root = JSONObject(json)
            require(root.optInt("version", 1) == 1) { "Unsupported transfer description version" }
            val kind = root.getInt("kind")
            require(kind in 1..3) { "Unknown transfer kind" }
            val stepsJson = root.getJSONArray("steps")
            require(stepsJson.length() in 1..MAX_STEPS) { "A transfer needs 1-$MAX_STEPS steps" }
            val steps = List(stepsJson.length()) { StepSpec.parse(stepsJson.getJSONObject(it)) }
            val names = steps.mapNotNull { it.name }
            require(names.size == names.toSet().size) { "Step names must be unique" }
            val retry = root.optJSONObject("retry")
            return TransferSpec(
                raw = root,
                kind = kind,
                tag = root.optStringOrNull("tag")?.also { require(it.matches(KEY)) { "Invalid tag" } },
                unique = root.optStringOrNull("unique")?.also { require(it.matches(KEY)) { "Invalid unique key" } },
                network = root.optInt("network", 1).takeIf { it in 1..3 } ?: error("Unknown network requirement"),
                retry = RetryPolicy(
                    times = (retry?.optInt("times", 3) ?: 3).coerceIn(0, 20),
                    backoff = retry?.optInt("backoff", 2) ?: 2,
                    delaySeconds = (retry?.optLong("delaySeconds", 30) ?: 30).coerceIn(10, 18_000),
                ),
                notification = root.optJSONObject("notification")?.let(NotificationSpec::parse),
                transcode = root.optJSONObject("transcode"),
                steps = steps,
            )
        }

        private val KEY = Regex("[A-Za-z0-9_.:/-]{1,128}")
    }
}

internal data class RetryPolicy(val times: Int, val backoff: Int, val delaySeconds: Long)

internal data class NotificationSpec(
    val title: String,
    val text: String?,
    val progress: Boolean,
    val channel: String?,
    val completed: String?,
    val failed: String?,
) {
    companion object {
        fun parse(json: JSONObject) = NotificationSpec(
            title = json.getString("title").take(200),
            text = json.optStringOrNull("text")?.take(200),
            progress = json.optBoolean("progress", false),
            channel = json.optStringOrNull("channel")?.take(200),
            completed = json.optStringOrNull("completed")?.take(200),
            failed = json.optStringOrNull("failed")?.take(200),
        )
    }
}

internal data class HeaderSpec(
    val name: String,
    val value: String?,
    val prefix: String,
    val secretVault: String?,
    val secretValue: String?,
)

internal data class StepSpec(
    val name: String?,
    val method: String,
    val url: String,
    val headers: List<HeaderSpec>,
    val headersFrom: String?,
    val body: BodySpec?,
    val saveTo: String?,
) {
    companion object {
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private val HEADER = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")

        fun parse(json: JSONObject): StepSpec {
            val method = when (json.getInt("method")) {
                1 -> "GET"
                2 -> "POST"
                3 -> "PUT"
                4 -> "PATCH"
                5 -> "DELETE"
                else -> error("Unknown HTTP method")
            }
            val url = json.getString("url")
            require(url.length <= 8192 && url.none { it.isWhitespace() }) { "Invalid step URL" }
            if (!url.startsWith("{{")) TransferUrls.requireAllowed(url)
            val headers = json.optJSONArray("headers")?.let { array ->
                List(array.length()) { index ->
                    val header = array.getJSONObject(index)
                    val name = header.getString("name")
                    require(name.matches(HEADER)) { "Invalid header name" }
                    val secret = header.optJSONObject("secret")
                    HeaderSpec(
                        name = name,
                        value = header.optStringOrNull("value")?.also { value ->
                            require(value.none { it == '\r' || it == '\n' }) { "Invalid header value" }
                        },
                        prefix = header.optString("prefix", ""),
                        secretVault = secret?.optStringOrNull("vault"),
                        secretValue = secret?.optStringOrNull("value"),
                    ).also { require(it.value != null || it.secretVault != null || it.secretValue != null) { "Header $name has no value" } }
                }
            }.orEmpty()
            val body = json.optJSONObject("body")?.let(BodySpec::parse)
            require(body == null || method != "GET") { "GET steps cannot send a body" }
            return StepSpec(
                name = json.optStringOrNull("name")?.also { require(it.matches(NAME)) { "Invalid step name" } },
                method = method,
                url = url,
                headers = headers,
                headersFrom = json.optStringOrNull("headersFrom"),
                body = body,
                saveTo = json.optStringOrNull("saveTo")?.also(TransferPaths::requireRelative),
            )
        }
    }
}

internal sealed interface BodySpec {
    fun files(): List<String>

    data class Json(val value: Any) : BodySpec {
        override fun files() = emptyList<String>()
    }

    data class Form(val fields: JSONObject) : BodySpec {
        override fun files() = emptyList<String>()
    }

    data class File(val path: String, val mimeType: String) : BodySpec {
        override fun files() = listOf(path)
    }

    data class Multipart(val parts: List<PartSpec>) : BodySpec {
        override fun files() = parts.filterIsInstance<PartSpec.File>().map { it.path }
    }

    companion object {
        fun parse(json: JSONObject): BodySpec = when (json.getString("type")) {
            "json" -> Json(json.get("value"))
            "form" -> Form(json.optJSONObject("fields") ?: JSONObject())
            "file" -> File(
                json.getString("path").also(TransferPaths::requireRelative),
                json.getString("mimeType").also(::requireMime),
            )
            "multipart" -> {
                val parts = json.getJSONArray("parts")
                require(parts.length() in 1..64) { "Multipart bodies support 1-64 parts" }
                Multipart(List(parts.length()) { PartSpec.parse(parts.getJSONObject(it)) })
            }
            else -> error("Unknown body type")
        }
    }
}

internal sealed interface PartSpec {
    val name: String

    data class Field(override val name: String, val value: String) : PartSpec

    data class File(override val name: String, val path: String, val mimeType: String, val filename: String) : PartSpec

    companion object {
        fun parse(json: JSONObject): PartSpec {
            val name = json.getString("name")
            require(name.isNotEmpty() && name.length <= 255 && name.none { it == '"' || it == '\r' || it == '\n' }) {
                "Invalid multipart part name"
            }
            return when (json.getString("type")) {
                "field" -> Field(name, json.getString("value"))
                "file" -> File(
                    name = name,
                    path = json.getString("path").also(TransferPaths::requireRelative),
                    mimeType = json.getString("mimeType").also(::requireMime),
                    filename = json.getString("filename").also { filename ->
                        require(filename.isNotEmpty() && filename.length <= 255 && filename.none { it in "\"\r\n/\\" }) {
                            "Invalid multipart file name"
                        }
                    },
                )
                else -> error("Unknown multipart part")
            }
        }
    }
}

private val MIME = Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,63}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}")

private fun requireMime(value: String) = require(value.matches(MIME)) { "Invalid MIME type" }

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key).takeIf { it.isNotEmpty() } else null

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
