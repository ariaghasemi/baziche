package com.baziche.core.network

import com.baziche.core.common.ErrorParser
import retrofit2.HttpException
import java.io.IOException

/** Canonical client-side API failure. */
class ApiException(val code: String, message: String, val httpCode: Int) : IOException(message)

/** Map Retrofit/IO failures to ApiException with server error codes preserved. */
object ApiErrors {
    fun map(t: Throwable): ApiException {
        if (t is ApiException) return t
        if (t is HttpException) {
            val raw = try {
                t.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            val parsed = ErrorParser.parse(raw)
            return ApiException(parsed?.first ?: "INTERNAL", parsed?.second ?: "Request failed", t.code())
        }
        if (t is IOException) return ApiException("NETWORK", t.message ?: "Network error", 0)
        return ApiException("INTERNAL", t.message ?: "Unexpected error", 0)
    }

    /** Raw error body of an HttpException (for 409 conflict payloads). */
    fun rawBody(t: HttpException): String? = try {
        t.response()?.errorBody()?.string()
    } catch (_: Exception) {
        null
    }
}
