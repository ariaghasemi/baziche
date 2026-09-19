package com.baziche.core.data

import com.baziche.core.common.ApiResult
import com.baziche.core.data.crypto.PasswordStretcher
import com.baziche.core.data.repo.AuthRepository
import com.baziche.core.data.session.Session
import com.baziche.core.data.session.SessionStore
import com.baziche.core.network.ApiException
import com.baziche.core.network.ApiUser
import com.baziche.core.network.BazicheApi
import com.baziche.core.network.ChallengeRequest
import com.baziche.core.network.ChallengeResponse
import com.baziche.core.network.CommitRequest
import com.baziche.core.network.CreateProjectRequest
import com.baziche.core.network.CreateProjectResponse
import com.baziche.core.network.ErrorBody
import com.baziche.core.network.AssetUrlResponse
import com.baziche.core.network.AssetsResponse
import com.baziche.core.network.LoginRequest
import com.baziche.core.network.MeResponse
import com.baziche.core.network.MergeRequest
import com.baziche.core.network.MergeResponse
import com.baziche.core.network.OkResponse
import com.baziche.core.network.RestoreRequest
import com.baziche.core.network.RestoreResponse
import com.baziche.core.network.PlansResponse
import com.baziche.core.network.PresignRequest
import com.baziche.core.network.PresignResponse
import com.baziche.core.network.ProjectDetailResponse
import com.baziche.core.network.ProjectsResponse
import com.baziche.core.network.UploadAssetResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import com.baziche.core.network.RefreshRequest
import com.baziche.core.network.RegisterRequest
import com.baziche.core.network.RegistriesResponse
import com.baziche.core.network.RevisionsResponse
import com.baziche.core.network.SaveProjectRequest
import com.baziche.core.network.SaveProjectResponse
import com.baziche.core.network.SessionResponse
import com.baziche.core.network.SendEmailCodeRequest
import com.baziche.core.network.SendEmailCodeResponse
import com.baziche.core.network.VerifyEmailCodeRequest
import com.baziche.core.network.VerifyEmailCodeResponse
import com.baziche.core.network.ProfileUpdateRequest
import com.baziche.core.network.CreateBuildRequest
import com.baziche.core.network.CreateBuildResponse
import com.baziche.core.network.BuildsResponse
import com.baziche.core.network.BuildDetailResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSessionStore : SessionStore {
    private val state = MutableStateFlow<Session?>(null)
    override val sessionFlow: Flow<Session?> = state
    override fun cachedAccessToken(): String? = state.value?.accessToken
    override suspend fun save(session: Session) {
        state.value = session
    }
    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        state.value = state.value?.copy(accessToken = accessToken, refreshToken = refreshToken)
    }
    override suspend fun clear() {
        state.value = null
    }
}

/** Hand-written fake of the API (no MockWebServer needed). */
class FakeApi(
    var challenge: ChallengeResponse = ChallengeResponse(true, "ab".repeat(16), 100_000, "PBKDF2-HMAC-SHA512"),
    var session: SessionResponse? = null,
    var failWith: Throwable? = null,
) : BazicheApi {
    var lastRegister: RegisterRequest? = null
    override suspend fun sendEmailCode(body: SendEmailCodeRequest): SendEmailCodeResponse =
        SendEmailCodeResponse(success = true, cooldownSec = 60)
    override suspend fun verifyEmailCode(body: VerifyEmailCodeRequest): VerifyEmailCodeResponse =
        VerifyEmailCodeResponse(success = true, verified = true, verificationToken = "verified_token_123")
    override suspend fun updateProfile(body: ProfileUpdateRequest): MeResponse = throw NotImplementedError()
    override suspend fun createBuild(body: CreateBuildRequest): CreateBuildResponse = throw NotImplementedError()
    override suspend fun builds(): BuildsResponse = throw NotImplementedError()
    override suspend fun buildDetail(id: String): BuildDetailResponse = throw NotImplementedError()
    override suspend fun challenge(body: ChallengeRequest): ChallengeResponse = challenge
    override suspend fun register(body: RegisterRequest): SessionResponse {
        lastRegister = body
        failWith?.let { throw it }
        return session ?: SessionResponse(true, ApiUser("usr_1", body.phone, body.username, "active"), "AT", "RT", 900)
    }
    override suspend fun login(body: LoginRequest): SessionResponse {
        failWith?.let { throw it }
        return session ?: SessionResponse(true, ApiUser("usr_1", body.phone.orEmpty(), "u", "active"), "AT", "RT", 900)
    }
    override suspend fun refresh(body: RefreshRequest): SessionResponse {
        failWith?.let { throw it }
        return SessionResponse(true, null, "AT2", "RT2", 900)
    }
    override suspend fun logout(body: RefreshRequest): OkResponse = OkResponse(true)
    override suspend fun me(): MeResponse = throw NotImplementedError()
    override suspend fun projects(): ProjectsResponse = throw NotImplementedError()
    override suspend fun createProject(body: CreateProjectRequest): CreateProjectResponse = throw NotImplementedError()
    override suspend fun project(id: String): ProjectDetailResponse = throw NotImplementedError()
    override suspend fun saveProject(id: String, body: SaveProjectRequest): SaveProjectResponse = throw NotImplementedError()
    override suspend fun deleteProject(id: String): OkResponse = throw NotImplementedError()
    override suspend fun revisions(id: String): RevisionsResponse = throw NotImplementedError()
    override suspend fun restore(id: String, body: RestoreRequest): RestoreResponse = throw NotImplementedError()
    override suspend fun merge(id: String, body: MergeRequest): MergeResponse = throw NotImplementedError()
    override suspend fun assets(projectId: String): AssetsResponse = throw NotImplementedError()
    override suspend fun assetUrl(id: String): AssetUrlResponse = throw NotImplementedError()
    override suspend fun presign(body: PresignRequest): PresignResponse = throw NotImplementedError()
    override suspend fun commitAsset(body: CommitRequest): OkResponse = throw NotImplementedError()
    override suspend fun uploadAsset(projectId: RequestBody, kind: RequestBody, file: MultipartBody.Part): UploadAssetResponse = throw NotImplementedError()
    override suspend fun registries(): RegistriesResponse = throw NotImplementedError()
    override suspend fun plans(): PlansResponse = throw NotImplementedError()
}

class AuthRepositoryTest {
    @Test
    fun `register stretches password client-side`() = runTest {
        val api = FakeApi()
        val repo = AuthRepository(api, FakeSessionStore())
        val res = repo.register("09121234567", "aria", "StrongPass123")
        assertTrue(res is ApiResult.Success)
        val sent = api.lastRegister!!
        // 128-hex client hash, never the raw password
        assertEquals(128, sent.clientHash.length)
        assertTrue(sent.clientHash.none { it == 'S' })
        assertEquals(100_000, sent.iterations)
    }

    @Test
    fun `register rejects bad phone without network`() = runTest {
        val repo = AuthRepository(FakeApi(), FakeSessionStore())
        val res = repo.register("123", "aria", "StrongPass123")
        assertTrue(res is ApiResult.Error && (res as ApiResult.Error).code == "INVALID_PHONE")
    }

    @Test
    fun `login maps 401 to INVALID_CREDENTIALS`() = runTest {
        val repo = AuthRepository(
            FakeApi(failWith = ApiException("INVALID_CREDENTIALS", "Invalid credentials", 401)),
            FakeSessionStore(),
        )
        val res = repo.login("09121234567", "WrongPass123")
        assertTrue(res is ApiResult.Error && (res as ApiResult.Error).code == "INVALID_CREDENTIALS")
    }

    @Test
    fun `stretch output matches server test vector shape`() {
        val salt = PasswordStretcher.fromHex("ab".repeat(16))
        val hex = PasswordStretcher.toHex(PasswordStretcher.stretch("StrongPass123", salt, 100_000))
        assertEquals(128, hex.length)
    }
}
