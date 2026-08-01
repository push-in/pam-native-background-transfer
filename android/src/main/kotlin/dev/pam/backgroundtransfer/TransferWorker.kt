package dev.pam.backgroundtransfer

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class TransferWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val kind = inputData.getInt(KIND, 1)
        return runCatching { if (kind == 1) download() else upload() }
            .fold({ Result.success(progress(kind, it.first, it.second)) }, { error ->
                if (runAttemptCount < 3) Result.retry() else Result.failure(progress(kind, 0, 0, error.message))
            })
    }

    private fun download(): Pair<Long, Long> {
        val destination = safeFile(inputData.getString(PATH) ?: error("path is required"))
        destination.parentFile?.mkdirs()
        val connection = connection("GET")
        val total = connection.contentLengthLong.coerceAtLeast(0)
        connection.inputStream.use { input -> destination.outputStream().use { output ->
            val buffer=ByteArray(64*1024); var transferred=0L
            while(true){ if(isStopped) error("Transfer cancelled"); val count=input.read(buffer); if(count<0)break; output.write(buffer,0,count); transferred+=count; setProgressAsync(progress(1,transferred,total)) }
            return transferred to total
        }}
    }

    private fun upload(): Pair<Long, Long> {
        val source=safeFile(inputData.getString(PATH)?:error("path is required")); require(source.isFile){"Upload source does not exist"}
        val connection=connection("PUT").apply{doOutput=true;setFixedLengthStreamingMode(source.length())}
        source.inputStream().use{input->connection.outputStream.use{output->val buffer=ByteArray(64*1024);var transferred=0L;while(true){if(isStopped)error("Transfer cancelled");val count=input.read(buffer);if(count<0)break;output.write(buffer,0,count);transferred+=count;setProgressAsync(progress(2,transferred,source.length()))}}}
        require(connection.responseCode in 200..299){"Upload failed with HTTP ${connection.responseCode}"}; return source.length() to source.length()
    }
    private fun connection(method:String)=(URI(inputData.getString(URL)?:error("url is required")).toURL().openConnection() as HttpURLConnection).apply{requestMethod=method;connectTimeout=30_000;readTimeout=60_000;instanceFollowRedirects=true}
    private fun safeFile(path:String):File{val root=applicationContext.filesDir.canonicalFile;val file=File(root,path).canonicalFile;require(file.path.startsWith(root.path+File.separator)){"Path escapes app files"};return file}
    private fun progress(kind:Int,transferred:Long,total:Long,message:String?=null)=Data.Builder().putInt(KIND,kind).putLong(TRANSFERRED,transferred).putLong(TOTAL,total).apply{message?.let{putString(MESSAGE,it)}}.build()
    companion object { const val KIND="kind";const val URL="url";const val PATH="path";const val TRANSFERRED="transferred";const val TOTAL="total";const val MESSAGE="message" }
}
