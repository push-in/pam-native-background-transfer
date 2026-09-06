package dev.pam.backgroundtransfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.UUID

class BackgroundTransferModule(context: Context) : NativeModule {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            if (method == "status") {
                val identifier = values.text("identifier")
                val pending = workManager.getWorkInfoById(UUID.fromString(identifier))
                pending.addListener({
                    val result = runCatching { snapshot(identifier, pending.get()) }
                    result.onSuccess { completion.success(it) }.onFailure { completion.failure() }
                }, java.util.concurrent.Executor { it.run() })
                return
            }
            when (method) {
                "enqueue" -> enqueue(values, completion)
                "cancel" -> cancel(values.text("identifier"), completion)
                else -> error("Unknown method: $method")
            }
        }.onFailure { completion.failure() }
    }

    private fun enqueue(values: Map<String, WireValue>, completion: ModuleCompletion) {
        val kind = values.integer("kind")
        require(kind == 1L || kind == 2L) { "Invalid transfer kind" }
        val networkCode = values.integer("network")
        require(networkCode in 1L..3L) { "Invalid network requirement" }
        val network = when (networkCode) {
            2L -> NetworkType.UNMETERED
            3L -> NetworkType.NOT_ROAMING
            else -> NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(
                Data.Builder()
                    .putInt(TransferWorker.KIND, kind.toInt())
                    .putString(TransferWorker.URL, values.text("url"))
                    .putString(TransferWorker.PATH, values.text("path"))
                    .build(),
            ).addTag(TAG).addTag(TransferIdentity.tag(kind.toInt())).build()
        val pending = workManager.enqueue(request).result
        pending.addListener({
            val result = runCatching { pending.get() }
            result.onSuccess { completion.success(mapOf("identifier" to WireValue.Text(request.id.toString()))) }
                .onFailure { completion.failure() }
        }, java.util.concurrent.Executor { it.run() })
    }

    private fun snapshot(identifier: String, info: WorkInfo?): Map<String, WireValue> {
        requireNotNull(info) { "Transfer not found" }
        val output = if (info.state == WorkInfo.State.RUNNING) info.progress else info.outputData
        return mapOf(
            "identifier" to WireValue.Text(identifier),
            "kind" to WireValue.Integer(TransferIdentity.kind(info.tags, output.getInt(TransferWorker.KIND, 0)).toLong()),
            "state" to WireValue.Integer(info.state.toTransferState()),
            "bytesTransferred" to WireValue.Integer(output.getLong(TransferWorker.TRANSFERRED, 0)),
            "bytesTotal" to WireValue.Integer(output.getLong(TransferWorker.TOTAL, 0)),
            "message" to WireValue.Text(output.getString(TransferWorker.MESSAGE).orEmpty()),
        )
    }

    private fun cancel(identifier: String, completion: ModuleCompletion) {
        val pending = workManager.cancelWorkById(UUID.fromString(identifier)).result
        pending.addListener({
            val result = runCatching { pending.get() }
            result.onSuccess { completion.success(emptyMap()) }.onFailure { completion.failure() }
        }, java.util.concurrent.Executor { it.run() })
    }

    private fun WorkInfo.State.toTransferState(): Long = when (this) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> 1
        WorkInfo.State.RUNNING -> 2
        WorkInfo.State.SUCCEEDED -> 3
        WorkInfo.State.CANCELLED -> 5
        WorkInfo.State.FAILED -> 4
    }
    private fun Map<String, WireValue>.text(key: String)=(get(key) as? WireValue.Text)?.value?:error("$key is required")
    private fun Map<String, WireValue>.integer(key: String)=(get(key) as? WireValue.Integer)?.value?:error("$key is required")
    private fun ModuleCompletion.success(values:Map<String,WireValue>)=complete(ModuleResultStatus.SUCCESS,WireMap.encode(values))
    private fun ModuleCompletion.failure()=complete(ModuleResultStatus.FAILURE,"Background transfer failure".toByteArray())
    private companion object { const val TAG="dev.pam.background-transfer" }
}
