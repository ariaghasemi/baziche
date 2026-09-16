package com.baziche.core.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit interface mirroring shared/api-v1.openAPI.yaml (Phase 1 surface). */
interface BazicheApi {
    @POST("auth/challenge")
    suspend fun challenge(@Body body: ChallengeRequest): ChallengeResponse

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

    @GET("assets")
    suspend fun assets(@Query("projectId") projectId: String): AssetsResponse

    @GET("assets/{id}/url")
    suspend fun assetUrl(@Path("id") id: String): AssetUrlResponse

    @GET("meta/registries")
    suspend fun registries(): RegistriesResponse

    @GET("meta/plans")
    suspend fun plans(): PlansResponse
}
