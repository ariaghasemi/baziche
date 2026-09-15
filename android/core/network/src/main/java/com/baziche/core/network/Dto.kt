package com.baziche.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ErrorBody(val code: String = "INTERNAL", val message: String = "")

// ---------- auth ----------
@Serializable
data class ChallengeRequest(val phone: String)

@Serializable
data class ChallengeResponse(
    val success: Boolean = false,
    val salt: String? = null,
    val iterations: Int? = null,
    val algorithm: String? = null,
    val error: ErrorBody? = null,
)

@Serializable
data class RegisterRequest(
    val phone: String,
    val username: String,
    val salt: String,
    val clientHash: String,
    val iterations: Int,
    val device: String? = null,
)

@Serializable
data class LoginRequest(val phone: String, val clientHash: String, val device: String? = null)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ApiUser(
    val id: String,
    val phone: String,
    val username: String,
    val status: String,
    val createdAt: Long? = null,
    val projects: Int? = null,
)

@Serializable
data class SessionResponse(
    val success: Boolean = false,
    val user: ApiUser? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Int? = null,
    val error: ErrorBody? = null,
)

@Serializable
data class Entitlement(
    val subscribed: Boolean = false,
    val planId: String = "FREE",
    val expiresAt: Long? = null,
    val freeBuild: String = "AVAILABLE",
    val buildsAllowed: Boolean = false,
)

@Serializable
data class MeResponse(
    val success: Boolean = false,
    val user: ApiUser? = null,
    val entitlement: Entitlement? = null,
    val error: ErrorBody? = null,
)

// ---------- projects ----------
@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val gameType: String,
    val formatVersion: Int? = null,
    val rev: Int,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class ProjectsResponse(val success: Boolean = false, val projects: List<ProjectDto> = emptyList(), val error: ErrorBody? = null)

@Serializable
data class CreateProjectRequest(val name: String, val gameType: String)

@Serializable
data class CreateProjectResponse(val success: Boolean = false, val project: ProjectDto? = null, val error: ErrorBody? = null)

@Serializable
data class ProjectDetailResponse(
    val success: Boolean = false,
    val project: ProjectDto? = null,
    val json: JsonObject? = null,
    val error: ErrorBody? = null,
)

@Serializable
data class SaveProjectRequest(val baseRev: Int, val name: String? = null, val json: JsonObject)

@Serializable
data class SaveProjectResponse(val success: Boolean = false, val rev: Int? = null, val error: ErrorBody? = null)

@Serializable
data class ConflictResponse(
    val success: Boolean = false,
    val error: ErrorBody? = null,
    val serverRev: Int? = null,
    val serverCopy: JsonObject? = null,
)

@Serializable
data class RevisionDto(val rev: Int, val bytes: Int, val createdAt: Long)

@Serializable
data class RevisionsResponse(val success: Boolean = false, val revisions: List<RevisionDto> = emptyList())

@Serializable
data class OkResponse(val success: Boolean = false, val error: ErrorBody? = null)

// ---------- meta ----------
@Serializable
data class GameTypeDto(
    val id: String,
    val name: String,
    val tier: Int,
    val features: List<String> = emptyList(),
    val orientation: String = "portrait",
    val description: String = "",
)

@Serializable
data class RegistriesResponse(
    val success: Boolean = false,
    val version: Int = 0,
    val gameTypes: List<GameTypeDto> = emptyList(),
)

@Serializable
data class PlanDto(val id: String, val title: String, val priceToman: Long? = null, val days: Int? = null)

@Serializable
data class PlansResponse(val success: Boolean = false, val plans: List<PlanDto> = emptyList())

// ---------- assets ----------
@Serializable
data class PresignRequest(val projectId: String, val kind: String, val hash: String, val bytes: Long, val contentType: String)

@Serializable
data class PresignResponse(val success: Boolean = false, val key: String? = null, val uploadUrl: String? = null, val expiresIn: Int? = null)

@Serializable
data class CommitRequest(val projectId: String, val key: String, val hash: String, val bytes: Long, val kind: String)
