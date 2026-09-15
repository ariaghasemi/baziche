package com.baziche.core.network

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * @param baseUrl e.g. http://10.0.2.2:8787/api/v1/ (trailing slash added if missing)
     * @param tokenProvider in-memory token (no blocking IO on the interceptor path)
     * Token refresh-on-401 is done in repositories (retry-once), not via OkHttp Authenticator,
     * to avoid runBlocking inside the network stack.
     */
    fun create(baseUrl: String, debug: Boolean, tokenProvider: () -> String?): BazicheApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val auth = Interceptor { chain ->
            val token = tokenProvider()
            val req = if (token.isNullOrBlank()) chain.request()
            else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            chain.proceed(req)
        }
        val logging = HttpLoggingInterceptor().apply { level = if (debug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BazicheApi::class.java)
    }
}
