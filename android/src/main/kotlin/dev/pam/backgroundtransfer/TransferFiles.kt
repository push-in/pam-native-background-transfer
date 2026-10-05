package dev.pam.backgroundtransfer

import android.content.Context
import java.io.File

/** Encrypted on-disk payloads of a transfer, kept outside backups. */
internal object TransferFiles {
    fun writeSpec(context: Context, id: String, json: String) =
        TransferCrypto.writeFile(File(TransferPaths.workDirectory(context, id), "spec.enc"), json)

    fun readSpec(context: Context, id: String): TransferSpec =
        TransferSpec.parse(TransferCrypto.readFile(File(TransferPaths.workDirectory(context, id), "spec.enc")))

    fun hasSpec(context: Context, id: String) = File(TransferPaths.workDirectory(context, id), "spec.enc").isFile

    fun writeProgress(context: Context, id: String, progress: TransferProgress) =
        TransferCrypto.writeFile(File(TransferPaths.workDirectory(context, id), "progress.enc"), progress.toJson())

    fun readProgress(context: Context, id: String): TransferProgress {
        val file = File(TransferPaths.workDirectory(context, id), "progress.enc")
        return if (file.isFile) runCatching { TransferProgress.fromJson(TransferCrypto.readFile(file)) }.getOrDefault(TransferProgress()) else TransferProgress()
    }

    fun resetProgress(context: Context, id: String) {
        File(TransferPaths.workDirectory(context, id), "progress.enc").delete()
    }

    fun delete(context: Context, id: String) {
        TransferPaths.workDirectory(context, id).deleteRecursively()
    }
}
