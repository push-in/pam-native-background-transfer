package dev.pam.backgroundtransfer

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files

class DownloadFileTest {
    @Test fun replacesOnlyCompleteDownload() {
        val root = Files.createTempDirectory("pam-download-test").toFile()
        try {
            val target = root.resolve("document").apply { writeText("old") }
            val count = DownloadFile.write("new".byteInputStream(), target, 3, { false }, {})
            assertEquals(3L, count)
            assertEquals("new", target.readText())
            assertEquals(listOf("document"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }

    @Test fun preservesPreviousFileAcrossFailures() {
        for (scenario in 0..3) {
            val root = Files.createTempDirectory("pam-download-failure").toFile()
            try {
                val target = root.resolve("document").apply { writeText("old") }
                val input = if (scenario == 0) object : InputStream() {
                    override fun read(): Int = throw IOException("interrupted")
                } else "partial".byteInputStream()
                var cancelled = false
                try {
                    DownloadFile.write(input, target, if (scenario == 3) 0 else 100, { cancelled }) {
                        if (scenario == 2) cancelled = true
                    }
                    fail("Failed transfer replaced destination")
                } catch (_: Exception) {
                    assertEquals("old", target.readText())
                    assertEquals(listOf("document"), root.list()!!.toList())
                }
            } finally { root.deleteRecursively() }
        }
    }
}
