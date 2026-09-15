package com.baziche.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtoParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `session response parses`() {
        val raw = """{"success":true,"user":{"id":"usr_1","phone":"+98912","username":"aria","status":"active"},"accessToken":"a","refreshToken":"r","expiresIn":900}"""
        val s = json.decodeFromString<SessionResponse>(raw)
        assertTrue(s.success)
        assertEquals("aria", s.user?.username)
        assertEquals(900, s.expiresIn)
    }

    @Test
    fun `registries response parses game types`() {
        val raw = """{"success":true,"version":1,"gameTypes":[{"id":"quiz","name":"Quiz","tier":1,"orientation":"portrait","description":"q"}]}"""
        val r = json.decodeFromString<RegistriesResponse>(raw)
        assertEquals(1, r.gameTypes.size)
        assertEquals("quiz", r.gameTypes[0].id)
    }

    @Test
    fun `conflict response parses`() {
        val raw = """{"success":false,"error":{"code":"REVISION_CONFLICT","message":"stale"},"serverRev":3,"serverCopy":{"formatVersion":1}}"""
        val c = json.decodeFromString<ConflictResponse>(raw)
        assertEquals("REVISION_CONFLICT", c.error?.code)
        assertEquals(3, c.serverRev)
    }
}
