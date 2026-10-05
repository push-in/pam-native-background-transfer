package dev.pam.backgroundtransfer

import android.content.Context
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.BooleanSupplier
import java.util.function.DoubleConsumer
import org.json.JSONObject

/**
 * Optional bridge to `pushinbr/pam-native-media` (0.4+), which owns the
 * Media3 codec stack. PAM Native compiles each plugin as an independent
 * Android library, so the dependency is resolved at runtime through the
 * stable `dev.pam.media.MediaTranscoding` entry point that media keeps
 * through R8. Apps that only upload never pay for Media3.
 */
internal object MediaTranscoderBridge {
    private const val ENTRY = "dev.pam.media.MediaTranscoding"

    private val method: Method? by lazy {
        runCatching {
            Class.forName(ENTRY).getMethod(
                "transcode",
                Context::class.java,
                File::class.java,
                File::class.java,
                String::class.java,
                BooleanSupplier::class.java,
                DoubleConsumer::class.java,
            )
        }.getOrNull()
    }

    fun available(): Boolean = method != null

    /** Returns media's `TranscodeResult` JSON (`path`, `width`, `height`, `durationMillis`, `bytes`, ...). */
    fun transcode(
        context: Context,
        source: File,
        destination: File,
        options: JSONObject,
        cancelled: () -> Boolean,
        progress: (Double) -> Unit,
    ): JSONObject {
        val entry = method ?: throw TransferFailure(
            "Video transcoding requires pushinbr/pam-native-media 0.4 or newer",
            retryable = false,
        )
        return try {
            JSONObject(
                entry.invoke(
                    null,
                    context.applicationContext,
                    source,
                    destination,
                    options.toString(),
                    BooleanSupplier { cancelled() },
                    DoubleConsumer { progress(it) },
                ) as String,
            )
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            throw TransferFailure("Video transcoding failed: ${cause.message}", retryable = false, cause = cause)
        }
    }
}
