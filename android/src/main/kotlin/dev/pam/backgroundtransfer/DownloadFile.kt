package dev.pam.backgroundtransfer

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object DownloadFile {
    fun write(input: InputStream, destination: File, expected: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): Long {
        val partial = File.createTempFile("pam-transfer-", ".part", destination.absoluteFile.parentFile)
        try {
            val transferred = partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    check(!cancelled()) { "Transfer cancelled" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0) { "Transfer made no progress" }
                    output.write(buffer, 0, count)
                    total += count
                    progress(total)
                }
                total
            }
            check(!cancelled()) { "Transfer cancelled" }
            check(expected < 0 || transferred == expected) { "Incomplete download" }
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return transferred
        } finally {
            partial.delete()
        }
    }
}
