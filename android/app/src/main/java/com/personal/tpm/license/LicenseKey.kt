package com.personal.tpm.license

import java.time.YearMonth
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The monthly key: 6 letters and digits, not case sensitive, the same for everybody, one per month.
 *
 * It is HMAC-SHA256(secret, "TPM|yyyy-MM|counter"); the first 30 bits are written as 6 symbols of [ALPHABET]
 * (no 0/O/1/I, which are easy to mix up). If the result has no letter or no digit the counter goes up by one until
 * it has both. tools/license_keygen.py does exactly the same. It is a light lock: the secret is inside the app.
 */
object MonthKey {
    const val LENGTH = 6
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun derive(secret: ByteArray, month: YearMonth, counter: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val digest = mac.doFinal("TPM|$month|$counter".toByteArray(Charsets.UTF_8))
        var bits = 0L
        for (i in 0 until 4) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
        return buildString {
            for (i in 0 until LENGTH) append(ALPHABET[((bits shr (27 - 5 * i)) and 31).toInt()])
        }
    }

    fun generate(secret: ByteArray, month: YearMonth): String {
        var counter = 0
        while (true) {
            val code = derive(secret, month, counter)
            if (code.any { it.isDigit() } && code.any { it.isLetter() }) return code
            counter++
        }
    }

    /** What was typed, ready to compare: letters and digits only, upper case (spaces and dashes are ignored). */
    fun normalize(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()
}
