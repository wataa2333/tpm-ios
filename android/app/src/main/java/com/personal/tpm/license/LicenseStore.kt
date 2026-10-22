package com.personal.tpm.license

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What stays on the phone: the month ("yyyy-MM") the last accepted key unlocked. */
data class StoredLicense(val month: String?)

interface LicenseStore {
    fun load(): StoredLicense
    fun save(stored: StoredLicense)
}

/** In memory, for tests and previews. */
class MemoryLicenseStore(private var stored: StoredLicense = StoredLicense(null)) : LicenseStore {
    override fun load() = stored
    override fun save(stored: StoredLicense) { this.stored = stored }
}

/**
 * The file is encrypted with an AES-GCM key that lives in the Android Keystore, so the
 * unlocked month cannot be read or swapped by looking into the app's files. A file that cannot be decrypted
 * (the Keystore was reset) is treated as empty: the key has to be entered again.
 */
class KeystoreLicenseStore(context: Context) : LicenseStore {
    private val file = File(context.applicationContext.filesDir, "license.bin")

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun load(): StoredLicense {
        val fresh = StoredLicense(null)
        if (!file.exists()) return fresh.also { save(it) }
        return try {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val text = String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            StoredLicense(text.ifEmpty { null })
        } catch (e: Exception) {
            fresh.also { save(it) }
        }
    }

    override fun save(stored: StoredLicense) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val plain = (stored.month ?: "").toByteArray(Charsets.UTF_8)
            file.writeBytes(cipher.iv + cipher.doFinal(plain))
        } catch (e: Exception) {
            // Nothing sensible to do: the next launch asks for the key again.
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "tpm_license_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
