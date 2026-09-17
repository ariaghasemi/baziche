package com.baziche.core.data

import com.baziche.core.data.crypto.PasswordStretcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStretcherTest {
    @Test
    fun `stretch is deterministic`() {
        val salt = PasswordStretcher.fromHex("00112233445566778899aabbccddeeff")
        val a = PasswordStretcher.stretch("StrongPass123", salt, 100_000)
        val b = PasswordStretcher.stretch("StrongPass123", salt, 100_000)
        assertEquals(64, a.size)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `different salt gives different hash`() {
        val a = PasswordStretcher.stretch("StrongPass123", PasswordStretcher.fromHex("00".repeat(16)), 100_000)
        val b = PasswordStretcher.stretch("StrongPass123", PasswordStretcher.fromHex("ff".repeat(16)), 100_000)
        assertFalse(a.contentEquals(b))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `weak iterations rejected`() {
        PasswordStretcher.stretch("StrongPass123", PasswordStretcher.generateSalt(), 10_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short password rejected`() {
        PasswordStretcher.stretch("short", PasswordStretcher.generateSalt(), 100_000)
    }

    @Test
    fun `hex roundtrip`() {
        val salt = PasswordStretcher.generateSalt()
        assertTrue(PasswordStretcher.fromHex(PasswordStretcher.toHex(salt)).contentEquals(salt))
    }
}
