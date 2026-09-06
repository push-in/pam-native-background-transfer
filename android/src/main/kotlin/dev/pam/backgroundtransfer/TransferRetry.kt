package dev.pam.backgroundtransfer

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal class TransferHttpException(val code: Int) : IOException("Transfer HTTP failure")

internal object TransferRetry {
    fun requireSuccess(code: Int) {
        if (code !in 200..299) throw TransferHttpException(code)
    }

    fun shouldRetry(error: Exception, attempt: Int, stopped: Boolean): Boolean {
        if (stopped || attempt >= 3) return false
        return when (error) {
            is TransferHttpException -> error.code in setOf(408, 429, 500, 502, 503, 504)
            is SocketTimeoutException, is ConnectException,
            is UnknownHostException, is SocketException -> true
            else -> false
        }
    }
}
