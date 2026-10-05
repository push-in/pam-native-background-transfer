package dev.pam.backgroundtransfer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class TemplateException(message: String) : IllegalArgumentException(message)

/**
 * Resolves `{{response.a.b}}`, `{{steps.name.a.0.b}}`, `{{transfer.id}}` and
 * `{{transfer.tag}}` against the responses of already completed steps.
 */
internal class TransferTemplates(
    private val transferId: String,
    private val tag: String?,
    private val last: Any?,
    private val named: Map<String, Any?>,
) {
    fun string(value: String): String = PATTERN.replace(value) { match ->
        when (val resolved = lookup(match.groupValues[1])) {
            is String -> resolved
            JSONObject.NULL, null -> ""
            else -> resolved.toString()
        }
    }

    /** Resolves templates inside JSON; a string that is exactly one template keeps the referenced JSON type. */
    fun json(value: Any?): Any? = when (value) {
        is String -> PATTERN.matchEntire(value.trim())?.takeIf { value.trim() == value }?.let { lookup(it.groupValues[1]) } ?: string(value)
        is JSONObject -> JSONObject().also { copy -> value.keys().forEach { key -> copy.put(key, json(value.get(key))) } }
        is JSONArray -> JSONArray().also { copy -> for (index in 0 until value.length()) copy.put(json(value.get(index))) }
        else -> value
    }

    fun lookup(path: String): Any? {
        val segments = path.split('.')
        var current: Any? = when (segments.first()) {
            "transfer" -> return when (segments.getOrNull(1)) {
                "id" -> transferId
                "tag" -> tag.orEmpty()
                else -> throw TemplateException("Unknown template {{$path}}")
            }
            "response" -> last
            "steps" -> {
                val name = segments.getOrNull(1) ?: throw TemplateException("Template {{$path}} needs a step name")
                if (!named.containsKey(name)) throw TemplateException("Step \"$name\" has not run yet")
                named[name]
            }
            else -> throw TemplateException("Unknown template {{$path}}")
        }
        val offset = if (segments.first() == "steps") 2 else 1
        for (segment in segments.drop(offset)) {
            current = when (current) {
                is JSONObject -> if (current.has(segment)) current.get(segment) else null
                is JSONArray -> segment.toIntOrNull()?.takeIf { it in 0 until current.length() }?.let(current::get)
                else -> null
            } ?: throw TemplateException("Template {{$path}} is missing from the response")
        }
        return current
    }

    companion object {
        private val PATTERN = Regex("""\{\{\s*([A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]+)*)\s*\}\}""")

        /** Parses a response body as JSON when possible, otherwise keeps the text. */
        fun parse(body: String): Any? {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return body
            return runCatching { JSONTokener(trimmed).nextValue() }.getOrDefault(body)
        }
    }
}

/** Encrypted resumable execution state: completed steps, their responses and transcoded files. */
internal data class TransferProgress(
    val next: Int = 0,
    val last: String? = null,
    val named: Map<String, String> = emptyMap(),
    val transcoded: Map<String, String> = emptyMap(),
) {
    fun toJson(): String = JSONObject()
        .put("next", next)
        .put("last", last ?: JSONObject.NULL)
        .put("named", JSONObject(named))
        .put("transcoded", JSONObject(transcoded))
        .toString()

    fun templates(id: String, tag: String?) = TransferTemplates(
        transferId = id,
        tag = tag,
        last = last?.let(TransferTemplates::parse),
        named = named.mapValues { TransferTemplates.parse(it.value) },
    )

    companion object {
        fun fromJson(json: String): TransferProgress {
            val root = JSONObject(json)
            fun map(key: String) = root.optJSONObject(key)?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }.orEmpty()
            return TransferProgress(root.optInt("next"), root.optStringOrNull("last"), map("named"), map("transcoded"))
        }
    }
}
