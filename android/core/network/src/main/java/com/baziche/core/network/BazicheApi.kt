package com.baziche.core.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit interface mirroring shared/api-v1.openAPI.yaml. */
interface BazicheApi {
    @POST("auth/challenge")
    suspend fun challenge(@Body body: ChallengeRequest): ChallengeResponse

    @POST("auth/email/send-code")
    suspend fun sendEmailCode(@Body body: SendEmailCodeRequest): SendEmailCodeResponse

    @POST("auth/email/verify-code")
    suspend fun verifyEmailCode(@Body body: VerifyEmailCodeRequest): VerifyEmailCodeResponse

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): SessionResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): SessionResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): SessionResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): OkResponse

    @GET("me")
    suspend fun me(): MeResponse

    @PATCH("me/profile")
    suspend fun updateProfile(@Body body: ProfileUpdateRequest): MeResponse

    @GET("projects")
    suspend fun projects(): ProjectsResponse

    @POST("projects")
    suspend fun createProject(@Body body: CreateProjectRequest): CreateProjectResponse

    @GET("projects/{id}")
    suspend fun project(@Path("id") id: String): ProjectDetailResponse

    @PATCH("projects/{id}")
    suspend fun saveProject(@Path("id") id: String, @Body body: SaveProjectRequest): SaveProjectResponse

    @DELETE("projects/{id}")
    suspend fun deleteProject(@Path("id") id: String): OkResponse

    @GET("projects/{id}/revisions")
    suspend fun revisions(@Path("id") id: String): RevisionsResponse

    @POST("projects/{id}/restore")
    suspend fun restore(@Path("id") id: String, @Body body: RestoreRequest): RestoreResponse

    @POST("projects/{id}/merge")
    suspend fun merge(@Path("id") id: String, @Body body: MergeRequest): MergeResponse

    @POST("assets/presign")
    suspend fun presign(@Body body: PresignRequest): PresignResponse

    @POST("assets/commit")
    suspend fun commitAsset(@Body body: CommitRequest): OkResponse

    @Multipart
    @POST("assets/upload")
    suspend fun uploadAsset(
        @Part("projectId") projectId: RequestBody,
        @Part("kind") kind: RequestBody,
        @Part file: MultipartBody.Part,
    ): UploadAssetResponse

    @GET("assets")
    suspend fun assets(@Query("projectId") projectId: String): AssetsResponse

    @GET("assets/{id}/url")
    suspend fun assetUrl(@Path("id") id: String): AssetUrlResponse

    @GET("meta/registries")
    suspend fun registries(): RegistriesResponse

    @GET("meta/plans")
    suspend fun plans(): PlansResponse

    // ---------- builds ----------
    @POST("builds")
    suspend fun createBuild(@Body body: CreateBuildRequest): CreateBuildResponse

    @GET("builds")
    suspend fun builds(): BuildsResponse

    @GET("builds/{id}")
    suspend fun buildDetail(@Path("id") id: String): BuildDetailResponse
}
