package com.personal.tpm.license

/**
 * The secret the monthly keys come from. Run `python tools/license_keygen.py init` once on the PC, paste the two
 * lines it prints here, and rebuild. Until then the app says the licence is not set up and cannot be unlocked.
 * The same secret stays in `.private/` on the PC (never in the repository).
 */
object LicenseSecret {
    // Two pieces only so the whole secret is not one searchable string in the APK.
    private const val PART_A = ""
    private const val PART_B = ""

    val bytes: ByteArray? get() = parseHex(PART_A + PART_B)

    internal fun parseHex(hex: String): ByteArray? {
        if (hex.length < 32 || hex.length % 2 != 0) return null
        return try { ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (e: NumberFormatException) { null }
    }
}
