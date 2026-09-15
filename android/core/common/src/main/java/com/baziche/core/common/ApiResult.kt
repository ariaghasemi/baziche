package com.baziche.core.common

import org.json.JSONObject

/** Uniform result for repository calls. Error codes match shared/error-codes.md. */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Error(val code: String, val message: String, val httpCode: Int = 0) : ApiResult<Nothing>
}

/** Parse `{success:false,error:{code,message}}` envelopes without extra deps. */
object ErrorParser {
    fun parse(body: String?): Pair<String, String>? {
        if (body.isNullOrBlank()) return null
        return try {
            val e = JSONObject(body).optJSONObject("error") ?: return null
            Pair(e.optString("code", "INTERNAL"), e.optString("message", "Internal error"))
        } catch (_: Exception) {
            null
        }
    }
}
