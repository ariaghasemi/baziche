package com.baziche.core.data.repo

import com.baziche.core.common.ApiResult
import com.baziche.core.data.crypto.PasswordStretcher
import com.baziche.core.data.session.Session
import com.baziche.core.data.session.SessionStore
import com.baziche.core.data.util.PhoneUtils
import com.baziche.core.network.ApiErrors
import com.baziche.core.network.BazicheApi
import com.baziche.core.network.ChallengeRequest
import com.baziche.core.network.Entitlement
import com.baziche.core.network.LoginRequest
import com.baziche.core.network.ProfileUpdateRequest
import com.baziche.core.network.RefreshRequest
import com.baziche.core.network.RegisterRequest
import com.baziche.core.network.SendEmailCodeRequest
import com.baziche.core.network.SendEmailCodeResponse
import com.baziche.core.network.VerifyEmailCodeRequest
import com.baziche.core.network.VerifyEmailCodeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException

data class MeInfo(
    val userId: String,
    val username: String,
    val phone: String,
    val projects: Int,
    val entitlement: Entitlement,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val avatar: String? = null,
)

class AuthRepository(
    private val api: BazicheApi,
    private val sessions: SessionStore,
    private val deviceLabel: () -> String = { "android" },
) {
    private val refreshMutex = Mutex()

    suspend fun currentSession(): Session? = sessions.sessionFlow.first()

    /** Send 6-digit verification code to a Gmail address */
    suspend fun sendEmailCode(email: String, phone: String? = null): ApiResult<SendEmailCodeResponse> = withContext(Dispatchers.IO) {
        val trimmed = email.trim().lowercase()
        if (!trimmed.endsWith("@gmail.com")) {
            return@withContext ApiResult.Error("INVALID_EMAIL", "فقط ایمیل‌های gmail.com@ مجاز هستند")
        }
        try {
            val res = api.sendEmailCode(SendEmailCodeRequest(trimmed, phone))
            if (!res.success) {
                return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", res.error?.message ?: "ارسال کد با خطا مواجه شد")
            }
            ApiResult.Success(res)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "خطا در ارتباط با سرور", e.httpCode)
        }
    }

    /** Verify 6-digit verification code */
    suspend fun verifyEmailCode(email: String, code: String): ApiResult<VerifyEmailCodeResponse> = withContext(Dispatchers.IO) {
        val trimmed = email.trim().lowercase()
        val trimmedCode = code.trim()
        if (trimmedCode.length != 6) {
            return@withContext ApiResult.Error("INVALID_CODE", "کد تأیید باید ۶ رقمی باشد")
        }
        try {
            val res = api.verifyEmailCode(VerifyEmailCodeRequest(trimmed, trimmedCode))
            if (!res.success || !res.verified) {
                return@withContext ApiResult.Error(res.error?.code ?: "INVALID_CODE", res.error?.message ?: "کد وارد شده معتبر نیست")
            }
            ApiResult.Success(res)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "خطا در تأیید کد", e.httpCode)
        }
    }

    suspend fun register(
        phoneRaw: String,
        username: String,
        password: String,
        email: String? = null,
        verificationCode: String? = null,
        verificationToken: String? = null,
    ): ApiResult<Session> = withContext(Dispatchers.IO) {
        val phone = PhoneUtils.normalize(phoneRaw) ?: return@withContext ApiResult.Error("INVALID_PHONE", "Invalid phone")
        if (password.length < 8) return@withContext ApiResult.Error("WEAK_PASSWORD_PARAMS", "Password too short")
        if (email != null && !email.trim().lowercase().endsWith("@gmail.com")) {
            return@withContext ApiResult.Error("INVALID_EMAIL", "ایمیل باید به gmail.com@ ختم شود")
        }
        try {
            val salt = PasswordStretcher.generateSalt()
            val hash = PasswordStretcher.stretch(password, salt, PasswordStretcher.DEFAULT_ITERATIONS)
            val res = api.register(
                RegisterRequest(
                    phone = phone,
                    username = username.trim(),
                    salt = PasswordStretcher.toHex(salt),
                    clientHash = PasswordStretcher.toHex(hash),
                    iterations = PasswordStretcher.DEFAULT_ITERATIONS,
                    device = deviceLabel(),
                    email = email?.trim()?.lowercase(),
                    verificationCode = verificationCode?.trim(),
                    verificationToken = verificationToken?.trim(),
                ),
            )
            val user = res.user
            val accessToken = res.accessToken
            val refreshToken = res.refreshToken
            if (!res.success || user == null || accessToken == null || refreshToken == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", res.error?.message ?: "Register failed")
            }
            val s = Session(user.id, user.username, user.phone, accessToken, refreshToken)
            sessions.save(s)
            ApiResult.Success(s)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "Register failed", e.httpCode)
        }
    }

    suspend fun login(identifierRaw: String, password: String): ApiResult<Session> = withContext(Dispatchers.IO) {
        val isEmail = identifierRaw.contains("@")
        val identifier = if (isEmail) identifierRaw.trim().lowercase() else (PhoneUtils.normalize(identifierRaw) ?: return@withContext ApiResult.Error("INVALID_PHONE", "Invalid phone"))

        try {
            val chReq = if (isEmail) ChallengeRequest(email = identifier) else ChallengeRequest(phone = identifier)
            val ch = api.challenge(chReq)
            val salt = ch.salt
            val iterations = ch.iterations
            if (!ch.success || salt.isNullOrEmpty() || iterations == null) {
                return@withContext ApiResult.Error(ch.error?.code ?: "INTERNAL", "Challenge failed")
            }
            val hash = PasswordStretcher.stretch(password, PasswordStretcher.fromHex(salt), iterations)
            val loginReq = if (isEmail) {
                LoginRequest(email = identifier, clientHash = PasswordStretcher.toHex(hash), device = deviceLabel())
            } else {
                LoginRequest(phone = identifier, clientHash = PasswordStretcher.toHex(hash), device = deviceLabel())
            }
            val res = api.login(loginReq)
            val user = res.user
            val accessToken = res.accessToken
            val refreshToken = res.refreshToken
            if (!res.success || user == null || accessToken == null || refreshToken == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "INVALID_CREDENTIALS", res.error?.message ?: "Login failed")
            }
            val s = Session(user.id, user.username, user.phone, accessToken, refreshToken)
            sessions.save(s)
            ApiResult.Success(s)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "Login failed", e.httpCode)
        }
    }

    /** Mutex-guarded refresh; returns true if session is (now) valid. */
    suspend fun refreshSync(): Boolean = refreshMutex.withLock {
        val cur = sessions.sessionFlow.first() ?: return false
        try {
            val res = api.refresh(RefreshRequest(cur.refreshToken))
            val accessToken = res.accessToken
            val refreshToken = res.refreshToken
            if (!res.success || accessToken == null || refreshToken == null) {
                sessions.clear()
                return false
            }
            sessions.updateTokens(accessToken, refreshToken)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Run [block], retry once after refresh on HTTP 401.
     * The original throwable is rethrown untouched (error bodies stay readable for callers).
     */
    suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            if (t is HttpException && t.code() == 401) {
                if (refreshSync()) return block()
            }
            throw t
        }
    }

    suspend fun me(): ApiResult<MeInfo> = withContext(Dispatchers.IO) {
        try {
            val res = withAuthRetry { api.me() }
            val user = res.user
            val entitlement = res.entitlement
            if (!res.success || user == null || entitlement == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", "Failed to load profile")
            }
            ApiResult.Success(
                MeInfo(
                    userId = user.id,
                    username = user.username,
                    phone = user.phone,
                    projects = user.projects ?: 0,
                    entitlement = entitlement,
                    email = user.email,
                    emailVerified = user.emailVerified ?: false,
                    avatar = user.avatar,
                ),
            )
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "Failed", e.httpCode)
        }
    }

    suspend fun updateProfile(username: String? = null, avatar: String? = null): ApiResult<MeInfo> = withContext(Dispatchers.IO) {
        try {
            val res = withAuthRetry { api.updateProfile(ProfileUpdateRequest(username?.trim(), avatar)) }
            val user = res.user
            val entitlement = res.entitlement
            if (!res.success || user == null || entitlement == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", "Failed to update profile")
            }
            ApiResult.Success(
                MeInfo(
                    userId = user.id,
                    username = user.username,
                    phone = user.phone,
                    projects = user.projects ?: 0,
                    entitlement = entitlement,
                    email = user.email,
                    emailVerified = user.emailVerified ?: false,
                    avatar = user.avatar,
                ),
            )
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "Failed", e.httpCode)
        }
    }

    suspend fun logout(): ApiResult<Unit> = withContext(Dispatchers.IO) {
        val cur = sessions.sessionFlow.first()
        try {
            if (cur != null) api.logout(RefreshRequest(cur.refreshToken))
        } catch (_: Throwable) {
            // Best effort: local logout proceeds regardless.
        } finally {
            sessions.clear()
        }
        ApiResult.Success(Unit)
    }
}
