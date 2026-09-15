package com.baziche.core.data.crypto

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Client-side password stretching (layer 1 of the two-layer scheme).
 * PBKDF2-HMAC-SHA512, 512-bit output, per-user random salt. Pure JDK — no deps, unit testable.
 * The server NEVER sees the raw password; it only applies a peppered SHA-256 (layer 2).
 */
object PasswordStretcher {
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 2_000_000
    const val DEFAULT_ITERATIONS = 100_000
    const val SALT_BYTES = 16

    fun generateSalt(bytes: Int = SALT_BYTES): ByteArray {
        require(bytes in 8..64) { "bad salt size" }
        val s = ByteArray(bytes)
        SecureRandom().nextBytes(s)
        return s
    }

    fun stretch(password: String, salt: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(password.length >= 8) { "password too short" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 512)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { it.toInt().and(0xff).toString(16).padStart(2, '0') }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "bad hex" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
