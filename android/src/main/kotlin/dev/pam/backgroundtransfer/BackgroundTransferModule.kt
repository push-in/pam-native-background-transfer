package dev.pam.backgroundtransfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BackgroundTransferModule(context: Context) : NativeModule, AutoCloseable {
    private val context = context.applicationContext
    private val store = TransferStore.get(this.context)
    private val vault = SecretVault(this.context)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pam-background-transfer") }
    private val watches = ConcurrentHashMap<Long, WatchChannel>()
    private val nextSubscription = AtomicLong(1)

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        val values = runCatching { WireMap.decode(payload) }.getOrElse {
            completion.failure(it)
            return
        }
        when (method) {
            "watchNext" -> runCatching { watches[values.integer("subscription")] ?: error("Watch is closed") }
                .onSuccess { it.next(completion) }
                .onFailure { completion.failure(it) }
            else -> executor.execute {
                runCatching {
                    when (method) {
                        "enqueue" -> enqueue(values.text("spec"))
                        "status" -> status(values.text("identifier"))
                        "list" -> mapOf("transfers" to WireValue.Text(store.toJsonArray(store.all(values.optionalText("tag"))).toString()))
                        "watch" -> watch(values.text("identifier"))
                        "unwatch" -> unwatch(values.integer("subscription"))
                        "cancel" -> cancel(values.text("identifier"))
                        "retry" -> retry(values.text("identifier"))
                        "prune" -> prune(values.integer("olderThanDays"))
                        "secretPut" -> emptyMap<String, WireValue>().also { vault.put(values.text("name"), values.text("value")) }
                        "secretForget" -> emptyMap<String, WireValue>().also { vault.forget(values.text("name")) }
                        else -> error("Unknown method: $method")
                    }
                }.onSuccess { completion.success(it) }.onFailure { completion.failure(it) }
            }
        }
    }

    private fun enqueue(json: String): Map<String, WireValue> {
        val spec = TransferSpec.parse(json)
        spec.unique?.let { key -> store.findUnfinishedUnique(key)?.let { return it.toWire() } }
        spec.files().forEach { TransferPaths.resolve(context, it, mustExist = true) }
        if (spec.transcode != null) {
            require(MediaTranscoderBridge.available()) { "Video transcoding requires pushinbr/pam-native-media 0.4 or newer" }
        }
        val id = UUID.randomUUID().toString()
        TransferFiles.writeSpec(context, id, json)
        val total = spec.files().distinct().sumOf { TransferPaths.resolve(context, it, mustExist = true).length() }
        val record = TransferRecord(id = id, kind = spec.kind, steps = spec.steps.size, total = total, tag = spec.tag, unique = spec.unique)
        store.put(record)
        schedule(id, spec, ExistingWorkPolicy.KEEP)
        return record.toWire()
    }

    private fun schedule(id: String, spec: TransferSpec, policy: ExistingWorkPolicy) {
        val network = when (spec.network) {
            2 -> NetworkType.UNMETERED
            3 -> NetworkType.NOT_ROAMING
            else -> NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(
                if (spec.retry.backoff == 1) BackoffPolicy.LINEAR else BackoffPolicy.EXPONENTIAL,
                spec.retry.delaySeconds,
                TimeUnit.SECONDS,
            )
            .addTag(TAG)
            .apply {
                spec.tag?.let { addTag("$TAG.tag.$it") }
                if (spec.notification != null) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)
    }

    private fun status(id: String): Map<String, WireValue> = store.get(id)?.toWire() ?: error("Transfer not found")

    private fun watch(id: String): Map<String, WireValue> {
        val current = store.get(id) ?: error("Transfer not found")
        val subscription = nextSubscription.getAndIncrement()
        val channel = WatchChannel()
        channel.offer(current)
        channel.unsubscribe = store.observe(id, channel::offer)
        watches[subscription] = channel
        return mapOf("subscription" to WireValue.Integer(subscription))
    }

    private fun unwatch(subscription: Long): Map<String, WireValue> {
        watches.remove(subscription)?.close()
        return emptyMap()
    }

    private fun cancel(id: String): Map<String, WireValue> {
        val record = store.get(id) ?: error("Transfer not found")
        if (!TransferStates.finished(record.state)) {
            store.update(id) { it.copy(state = TransferStates.CANCELLED, stage = TransferStages.DONE, message = "Cancelled") }
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        return emptyMap()
    }

    private fun retry(id: String): Map<String, WireValue> {
        val record = store.get(id) ?: error("Transfer not found")
        require(record.state == TransferStates.FAILED || record.state == TransferStates.CANCELLED) { "Only failed or cancelled transfers can be retried" }
        require(TransferFiles.hasSpec(context, id)) { "Transfer payload is no longer available" }
        val spec = TransferFiles.readSpec(context, id)
        store.update(id) { it.copy(state = TransferStates.QUEUED, stage = TransferStages.WAITING, attempt = 0, message = "", statusCode = 0, responseBody = "") }
        schedule(id, spec, ExistingWorkPolicy.REPLACE)
        return record.toWire()
    }

    private fun prune(days: Long): Map<String, WireValue> {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.coerceIn(0, 3650))
        val removed = store.all().filter { TransferStates.finished(it.state) && it.updatedAt <= cutoff }
        removed.forEach { record ->
            TransferFiles.delete(context, record.id)
            store.remove(record.id)
        }
        WorkManager.getInstance(context).pruneWork()
        return mapOf("removed" to WireValue.Integer(removed.size.toLong()))
    }

    override fun close() {
        watches.values.forEach(WatchChannel::close)
        watches.clear()
        executor.shutdown()
    }

    /** Conflated long-poll channel: `watchNext` receives the newest snapshot not yet delivered. */
    private class WatchChannel {
        private var pending: TransferRecord? = null
        private var waiter: ModuleCompletion? = null
        private var closed = false
        var unsubscribe: (() -> Unit)? = null

        fun offer(record: TransferRecord) {
            val deliver = synchronized(this) {
                if (closed) return
                val current = waiter
                if (current == null) pending = record else waiter = null
                current
            }
            deliver?.success(record.toWire())
        }

        fun next(completion: ModuleCompletion) {
            val ready = synchronized(this) {
                when {
                    closed -> null.also { completion.failure(IllegalStateException("Watch is closed")) }
                    pending != null -> pending.also { pending = null }
                    waiter != null -> null.also { completion.failure(IllegalStateException("Watch already pending")) }
                    else -> null.also { waiter = completion }
                }
            }
            ready?.let { completion.success(it.toWire()) }
        }

        fun close() {
            val current = synchronized(this) {
                if (closed) return
                closed = true
                pending = null
                waiter.also { waiter = null }
            }
            unsubscribe?.invoke()
            current?.failure(IllegalStateException("Watch stopped"))
        }
    }

    private companion object {
        const val TAG = "dev.pam.background-transfer"

        fun workName(id: String) = "$TAG.$id"
    }
}

private fun Map<String, WireValue>.text(key: String) = (get(key) as? WireValue.Text)?.value ?: error("$key is required")

private fun Map<String, WireValue>.optionalText(key: String) = (get(key) as? WireValue.Text)?.value?.takeIf { it.isNotEmpty() }

private fun Map<String, WireValue>.integer(key: String) = (get(key) as? WireValue.Integer)?.value ?: error("$key is required")

private fun ModuleCompletion.success(values: Map<String, WireValue>) = complete(ModuleResultStatus.SUCCESS, WireMap.encode(values))

private fun ModuleCompletion.failure(error: Throwable) =
    complete(ModuleResultStatus.FAILURE, (error.message ?: "Background transfer failure").toByteArray())
