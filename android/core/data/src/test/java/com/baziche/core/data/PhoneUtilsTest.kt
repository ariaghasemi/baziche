package com.baziche.core.data

import com.baziche.core.data.util.PhoneUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneUtilsTest {
    @Test
    fun `normalize iranian numbers`() {
        assertEquals("+989121234567", PhoneUtils.normalize("09121234567"))
        assertEquals("+989121234567", PhoneUtils.normalize("+989121234567"))
        assertEquals("+989121234567", PhoneUtils.normalize("00989121234567"))
        assertEquals("+989121234567", PhoneUtils.normalize("0912 123 4567"))
    }

    @Test
    fun `reject invalid`() {
        assertNull(PhoneUtils.normalize("123"))
        assertNull(PhoneUtils.normalize("02122334455"))
        assertNull(PhoneUtils.normalize("+12125551234"))
        assertNull(PhoneUtils.normalize(""))
    }
}
