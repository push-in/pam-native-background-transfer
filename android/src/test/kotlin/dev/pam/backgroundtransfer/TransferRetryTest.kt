package dev.pam.backgroundtransfer

import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRetryTest {
    @Test fun permanentFailuresDoNotRepeatTheUpload() {
        for (code in listOf(301, 307, 400, 401, 403, 404, 409, 413, 422, 501)) {
            assertFalse(TransferRetry.shouldRetry(TransferHttpException(code), 0, false))
        }
        for (error in listOf(FileNotFoundException(), IOException("Disk failure"),
            IllegalArgumentException(), SSLHandshakeException("Certificate failure"))) {
            assertFalse(TransferRetry.shouldRetry(error, 0, false))
        }
    }

    @Test fun transientFailuresHaveBoundedRetriesAndRespectCancellation() {
        val errors = listOf(SocketTimeoutException(), UnknownHostException()) +
            listOf(408, 429, 500, 502, 503, 504).map(::TransferHttpException)
        for (error in errors) {
            for (attempt in 0..2) assertTrue(TransferRetry.shouldRetry(error, attempt, false))
            assertFalse(TransferRetry.shouldRetry(error, 3, false))
            assertFalse(TransferRetry.shouldRetry(error, 0, true))
        }
    }

    @Test fun onlySuccessfulHttpResponsesAreAccepted() {
        for (code in listOf(200, 201, 204, 299)) TransferRetry.requireSuccess(code)
        for (code in listOf(301, 403, 429, 503)) {
            try {
                TransferRetry.requireSuccess(code)
                throw AssertionError("Unsuccessful HTTP response accepted")
            } catch (error: TransferHttpException) {
                assertTrue(error.code == code)
            }
        }
    }
}
