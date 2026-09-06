package dev.pam.backgroundtransfer

import org.junit.Assert.*
import org.junit.Test

class TransferHeadersTest {
    @Test fun permitsSignedUploadMetadata() {
        TransferHeaders.validate(mapOf("Content-Type" to "image/png", "x-amz-acl" to "private"))
    }
    @Test fun rejectsUnsafeAndConflictingHeaders() {
        for (headers in listOf(
            mapOf("Host" to "other.test"), mapOf("Content-Length" to "5"),
            mapOf("X-Test" to "a\r\nInjected: yes"), mapOf("X-Test" to "a", "x-test" to "b"),
            mapOf("bad name" to "value")
        )) {
            try {
                TransferHeaders.validate(headers)
                fail("Invalid headers accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
