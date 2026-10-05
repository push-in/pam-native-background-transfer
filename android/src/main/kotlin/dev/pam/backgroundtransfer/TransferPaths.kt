package dev.pam.backgroundtransfer

import android.content.Context
import java.io.File
import java.net.URI

/** Resolves PAM sandbox paths (the `FileReference` space rooted at `filesDir/pam-files`). */
internal object TransferPaths {
    fun requireRelative(path: String) {
        require(
            path.isNotEmpty() && path.length <= 1024 && !path.startsWith("/") && '\u0000' !in path && '\\' !in path &&
                path.split('/').none { it.isEmpty() || it == "." || it == ".." },
        ) { "Transfer paths must be relative sandbox paths" }
    }

    fun root(context: Context): File = File(context.filesDir, "pam-files").apply { mkdirs() }.canonicalFile

    fun resolve(context: Context, path: String, mustExist: Boolean): File {
        requireRelative(path)
        val root = root(context)
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Path escapes the application sandbox" }
        if (mustExist) require(file.isFile) { "File does not exist: $path" }
        return file
    }

    /** Private, non-backed-up working directory of one transfer (encrypted payloads, transcodes). */
    fun workDirectory(context: Context, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]{8,64}"))) { "Invalid transfer identifier" }
        return File(File(context.noBackupFilesDir, "pam-background-transfer"), id)
    }
}

internal object TransferUrls {
    private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    /** HTTPS everywhere; plain HTTP only for loopback test servers. */
    fun requireAllowed(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        require(uri != null && host != null && (scheme == "https" || (scheme == "http" && host in LOOPBACK))) {
            "Transfers require an HTTPS URL"
        }
    }
}
