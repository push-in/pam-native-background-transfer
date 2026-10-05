package dev.pam.backgroundtransfer

import android.content.Context
import android.content.SharedPreferences
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

internal object TransferStates {
    const val QUEUED = 1
    const val RUNNING = 2
    const val SUCCEEDED = 3
    const val FAILED = 4
    const val CANCELLED = 5
    const val RETRYING = 6

    fun finished(state: Int) = state == SUCCEEDED || state == FAILED || state == CANCELLED
}

internal object TransferStages {
    const val WAITING = 1
    const val TRANSCODING = 2
    const val UPLOADING = 3
    const val REQUESTING = 4
    const val DOWNLOADING = 5
    const val DONE = 6
}

/** Public, non-secret state of one transfer. Secrets and bodies live only in the encrypted payload. */
internal data class TransferRecord(
    val id: String,
    val kind: Int,
    val state: Int = TransferStates.QUEUED,
    val stage: Int = TransferStages.WAITING,
    val step: Int = 0,
    val steps: Int = 1,
    val transferred: Long = 0,
    val total: Long = 0,
    val attempt: Int = 0,
    val message: String = "",
    val tag: String? = null,
    val unique: String? = null,
    val statusCode: Int = 0,
    val responseBody: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("identifier", id).put("kind", kind).put("state", state).put("stage", stage)
        .put("step", step).put("steps", steps).put("bytesTransferred", transferred).put("bytesTotal", total)
        .put("attempt", attempt).put("message", message).put("tag", tag ?: "").put("unique", unique ?: "")
        .put("statusCode", statusCode).put("responseBody", responseBody)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    fun toWire(): Map<String, WireValue> = mapOf(
        "identifier" to WireValue.Text(id),
        "kind" to WireValue.Integer(kind.toLong()),
        "state" to WireValue.Integer(state.toLong()),
        "stage" to WireValue.Integer(stage.toLong()),
        "step" to WireValue.Integer(step.toLong()),
        "steps" to WireValue.Integer(steps.toLong()),
        "bytesTransferred" to WireValue.Integer(transferred),
        "bytesTotal" to WireValue.Integer(total),
        "attempt" to WireValue.Integer(attempt.toLong()),
        "message" to WireValue.Text(message),
        "tag" to WireValue.Text(tag.orEmpty()),
        "statusCode" to WireValue.Integer(statusCode.toLong()),
        "responseBody" to WireValue.Text(responseBody),
        "createdAt" to WireValue.Integer(createdAt),
        "updatedAt" to WireValue.Integer(updatedAt),
    )

    companion object {
        const val MAX_RESPONSE_CHARS = 256 * 1024

        fun fromJson(json: JSONObject) = TransferRecord(
            id = json.getString("identifier"),
            kind = json.optInt("kind", 2),
            state = json.optInt("state", TransferStates.FAILED),
            stage = json.optInt("stage", TransferStages.WAITING),
            step = json.optInt("step"),
            steps = json.optInt("steps", 1),
            transferred = json.optLong("bytesTransferred"),
            total = json.optLong("bytesTotal"),
            attempt = json.optInt("attempt"),
            message = json.optString("message"),
            tag = json.optStringOrNull("tag"),
            unique = json.optStringOrNull("unique"),
            statusCode = json.optInt("statusCode"),
            responseBody = json.optString("responseBody"),
            createdAt = json.optLong("createdAt"),
            updatedAt = json.optLong("updatedAt"),
        )
    }
}

/**
 * Process-wide persistent transfer registry with live listeners. Progress is
 * published to listeners at most every [PROGRESS_INTERVAL_MS] and persisted
 * at most once per second; lifecycle changes are always published and persisted.
 */
internal class TransferStore private constructor(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("dev.pam.background-transfer.v3", Context.MODE_PRIVATE)
    private val records = ConcurrentHashMap<String, TransferRecord>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(TransferRecord) -> Unit>>()
    private val lastPublished = ConcurrentHashMap<String, Long>()
    private val lastPersisted = ConcurrentHashMap<String, Long>()

    init {
        preferences.all.forEach { (key, value) ->
            (value as? String)?.let { raw -> runCatching { TransferRecord.fromJson(JSONObject(raw)) }.getOrNull() }
                ?.let { records[key] = it }
        }
    }

    fun get(id: String): TransferRecord? = records[id]

    fun all(tag: String? = null): List<TransferRecord> =
        records.values.filter { tag == null || it.tag == tag }.sortedByDescending { it.createdAt }

    fun findUnfinishedUnique(key: String): TransferRecord? =
        records.values.firstOrNull { it.unique == key && !TransferStates.finished(it.state) }

    fun put(record: TransferRecord) {
        records[record.id] = record
        persist(record)
        publish(record)
    }

    /** Lifecycle mutation: always persisted and published. */
    fun update(id: String, change: (TransferRecord) -> TransferRecord): TransferRecord? {
        var updated: TransferRecord? = null
        records.computeIfPresent(id) { _, current -> change(current).copy(updatedAt = System.currentTimeMillis()).also { updated = it } }
        updated?.let {
            persist(it)
            publish(it)
        }
        return updated
    }

    /** High-frequency byte progress: throttled persistence and publication. */
    fun progress(id: String, transferred: Long, total: Long) {
        var updated: TransferRecord? = null
        records.computeIfPresent(id) { _, current ->
            if (TransferStates.finished(current.state)) current
            else current.copy(transferred = transferred, total = total, updatedAt = System.currentTimeMillis()).also { updated = it }
        }
        val record = updated ?: return
        val now = System.currentTimeMillis()
        if (now - (lastPersisted[id] ?: 0) >= 1_000) {
            lastPersisted[id] = now
            persist(record)
        }
        if (now - (lastPublished[id] ?: 0) >= PROGRESS_INTERVAL_MS || (total > 0 && transferred >= total)) publish(record)
    }

    fun remove(id: String) {
        records.remove(id)
        lastPersisted.remove(id)
        lastPublished.remove(id)
        preferences.edit().remove(id).apply()
    }

    fun observe(id: String, listener: (TransferRecord) -> Unit): () -> Unit {
        val list = listeners.getOrPut(id) { CopyOnWriteArrayList() }
        list.add(listener)
        return { list.remove(listener) }
    }

    fun toJsonArray(records: List<TransferRecord>): JSONArray = JSONArray().apply { records.forEach { put(it.toJson()) } }

    private fun persist(record: TransferRecord) {
        preferences.edit().putString(record.id, record.toJson().toString()).apply()
    }

    private fun publish(record: TransferRecord) {
        lastPublished[record.id] = System.currentTimeMillis()
        listeners[record.id]?.forEach { listener -> runCatching { listener(record) } }
    }

    companion object {
        const val PROGRESS_INTERVAL_MS = 200L

        @Volatile private var instance: TransferStore? = null

        fun get(context: Context): TransferStore =
            instance ?: synchronized(this) { instance ?: TransferStore(context).also { instance = it } }
    }
}
