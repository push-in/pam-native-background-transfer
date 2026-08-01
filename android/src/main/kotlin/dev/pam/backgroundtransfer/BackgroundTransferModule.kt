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
            when (method) {
                "enqueue" -> enqueue(values)
                "status" -> status(values.text("identifier"))
                "cancel" -> cancel(values.text("identifier"))
                else -> error("Unknown method: $method")
            }
        }.onSuccess { completion.success(it) }.onFailure { completion.failure(it) }
    }

    private fun enqueue(values: Map<String, WireValue>): Map<String, WireValue> {
        val network = when (values.integer("network")) {
            2L -> NetworkType.UNMETERED
            3L -> NetworkType.NOT_ROAMING
            else -> NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setInputData(
                Data.Builder()
                    .putInt(TransferWorker.KIND, values.integer("kind").toInt())
                    .putString(TransferWorker.URL, values.text("url"))
                    .putString(TransferWorker.PATH, values.text("path"))
                    .build(),
            ).addTag(TAG).build()
        workManager.enqueue(request)
        return mapOf("identifier" to WireValue.Text(request.id.toString()))
    }

    private fun status(identifier: String): Map<String, WireValue> {
        val info = workManager.getWorkInfoById(UUID.fromString(identifier)).get()
            ?: return mapOf("state" to WireValue.Integer(4), "message" to WireValue.Text("Transfer not found"))
        val output = if (info.state == WorkInfo.State.RUNNING) info.progress else info.outputData
        return mapOf(
            "identifier" to WireValue.Text(identifier),
            "kind" to WireValue.Integer(output.getInt(TransferWorker.KIND, 1).toLong()),
            "state" to WireValue.Integer(info.state.toTransferState()),
            "bytesTransferred" to WireValue.Integer(output.getLong(TransferWorker.TRANSFERRED, 0)),
            "bytesTotal" to WireValue.Integer(output.getLong(TransferWorker.TOTAL, 0)),
            "message" to WireValue.Text(output.getString(TransferWorker.MESSAGE).orEmpty()),
        )
    }

    private fun cancel(identifier: String): Map<String, WireValue> {
        workManager.cancelWorkById(UUID.fromString(identifier))
        return emptyMap()
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
    private fun ModuleCompletion.failure(error:Throwable)=complete(ModuleResultStatus.FAILURE,(error.message?:"Background transfer failure").toByteArray())
    private companion object { const val TAG="dev.pam.background-transfer" }
}
