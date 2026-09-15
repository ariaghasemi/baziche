package com.baziche.core.data.util

/** Mirrors backend phone.ts. Returns +98XXXXXXXXXX or null. */
object PhoneUtils {
    fun normalize(raw: String): String? {
        val d = raw.replace(Regex("[\\s\\-()]"), "")
        val rest = when {
            Regex("^09\\d{9}$").matches(d) -> d.substring(1)
            Regex("^\\+989\\d{9}$").matches(d) -> d.substring(3)
            Regex("^00989\\d{9}$").matches(d) -> d.substring(4)
            else -> return null
        }
        return "+98$rest"
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null
}
