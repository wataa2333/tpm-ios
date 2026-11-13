package com.personal.tpm.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small text file in the app's own folder, encrypted with an AES-GCM key that lives in the Android Keystore
 * (it cannot be read by looking into the files). A file that cannot be decrypted reads as empty.
 */
class SecureFile(context: Context, name: String, private val alias: String) {
    private val file = File(context.applicationContext.filesDir, name)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized fun read(): String? {
        return try {
            val bytes = AtomicFile(file).readFully()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8).ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /** Null (or empty) removes the file. */
    fun write(text: String?) {
        if (text.isNullOrEmpty()) { file.delete(); return }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            file.writeBytes(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            // Nothing sensible to do: the next launch asks for the sign-in again.
        }
    }

    /** Commit a recoverable snapshot and report a failed write to its caller. */
    @Synchronized fun writeSnapshot(text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(encrypted)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        check(atomic.readFully().contentEquals(encrypted)) { "草稿写入未完成" }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
