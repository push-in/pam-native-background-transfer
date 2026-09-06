package dev.pam.backgroundtransfer

import org.json.JSONObject
import java.util.Locale

internal object TransferHeaders {
    private val reserved = setOf("host", "content-length", "transfer-encoding", "connection", "trailer", "upgrade")
    private val namePattern = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    fun decode(encoded: String): Map<String, String> {
        require(encoded.toByteArray(Charsets.UTF_8).size <= 4096) { "Transfer headers too large" }
        val json = JSONObject(encoded)
        require(json.length() <= 32) { "Too many transfer headers" }
        val headers = mutableMapOf<String, String>()
        json.keys().forEach { name ->
            val value = json.get(name)
            require(value is String) { "Invalid transfer header" }
            headers[name] = value
        }
        validate(headers)
        return headers
    }

    fun validate(headers: Map<String, String>) {
        require(headers.size <= 32) { "Too many transfer headers" }
        val seen = mutableSetOf<String>()
        headers.forEach { (name, value) ->
            val key = name.lowercase(Locale.ROOT)
            require(namePattern.matches(name) && seen.add(key) && key !in reserved) { "Invalid transfer header name" }
            require(value.none { it.code < 32 || it.code == 127 }) { "Invalid transfer header value" }
        }
    }
}
