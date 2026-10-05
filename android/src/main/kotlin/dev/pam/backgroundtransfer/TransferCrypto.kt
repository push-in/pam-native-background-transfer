package dev.pam.backgroundtransfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable Android Keystore key. */
internal object TransferCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "dev.pam.background-transfer.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        require(value.startsWith(PREFIX)) { "Unknown encrypted payload format" }
        val parts = value.removePrefix(PREFIX).split(':', limit = 2)
        require(parts.size == 2) { "Corrupt encrypted payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    /** Atomically replaces [target] with the encrypted [plain] text. */
    fun writeFile(target: File, plain: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(encrypt(plain), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "Unable to persist encrypted transfer data" }
        }
    }

    fun readFile(source: File): String = decrypt(source.readText(Charsets.UTF_8))

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }
}

/** Named credentials (`Secret::vault()`), resolved by the worker at request time. */
internal class SecretVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("dev.pam.background-transfer.vault", Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        requireName(name)
        require(value.isNotEmpty() && value.length <= 16_384 && value.none { it == '\r' || it == '\n' }) { "Invalid secret value" }
        check(preferences.edit().putString(name, TransferCrypto.encrypt(value)).commit()) { "Unable to store secret" }
    }

    fun get(name: String): String? = preferences.getString(name, null)?.let { runCatching { TransferCrypto.decrypt(it) }.getOrNull() }

    fun forget(name: String) {
        requireName(name)
        preferences.edit().remove(name).apply()
    }

    private fun requireName(name: String) = require(name.matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) { "Invalid secret name" }
}
