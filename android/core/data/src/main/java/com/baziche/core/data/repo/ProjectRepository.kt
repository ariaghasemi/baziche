package com.baziche.core.data.repo

import com.baziche.core.common.ApiResult
import com.baziche.core.data.db.CachedProject
import com.baziche.core.data.db.ProjectCache
import com.baziche.core.network.ApiErrors
import com.baziche.core.network.ApiException
import com.baziche.core.network.BazicheApi
import com.baziche.core.network.ConflictResponse
import com.baziche.core.network.CreateProjectRequest
import com.baziche.core.network.GameTypeDto
import com.baziche.core.network.MergeRequest
import com.baziche.core.network.NetworkModule
import com.baziche.core.network.ProjectDto
import com.baziche.core.network.RestoreRequest
import com.baziche.core.network.RevisionDto
import com.baziche.core.network.SaveProjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

sealed interface SaveResult {
    data class Saved(val rev: Int) : SaveResult
    data class Conflict(val serverRev: Int, val serverCopy: JsonObject?) : SaveResult
    /** No connection: JSON stored in the local dirty cache; SyncWorker pushes it later. */
    data object OfflineCached : SaveResult
    data class Failed(val code: String, val message: String) : SaveResult
}

data class ProjectDetail(val project: ProjectDto, val json: JsonObject?)

data class MergedProject(val merged: JsonObject, val conflicts: List<String>, val serverRev: Int)

class ProjectRepository(
    private val api: BazicheApi,
    private val auth: AuthRepository,
    private val cache: ProjectCache,
) {
    suspend fun listProjects(): ApiResult<List<ProjectDto>> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.projects() }
            if (!res.success) return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", "Failed to load projects")
            val now = System.currentTimeMillis()
            res.projects.forEach { p ->
                val prev = cache.get(p.id)
                cache.upsert(CachedProject(p.id, p.name, p.gameType, p.rev, prev?.json, p.updatedAt ?: now, prev?.dirty == true))
            }
            ApiResult.Success(res.projects)
        } catch (t: Throwable) {
            // Offline fallback: serve cache.
            val cached = cache.list()
            if (cached.isNotEmpty()) {
                ApiResult.Success(cached.map { ProjectDto(it.id, it.name, it.gameType, null, it.rev, null, it.updatedAt) })
            } else {
                val e = ApiErrors.map(t)
                ApiResult.Error(e.code, e.message ?: "Failed", e.httpCode)
            }
        }
    }

    suspend fun createProject(name: String, gameType: String): ApiResult<ProjectDto> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.createProject(CreateProjectRequest(name.trim(), gameType)) }
            if (!res.success || res.project == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", res.error?.message ?: "Create failed")
            }
            val p = res.project
            cache.upsert(CachedProject(p.id, p.name, p.gameType, p.rev, null, System.currentTimeMillis(), false))
            ApiResult.Success(p)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "Create failed", e.httpCode)
        }
    }

    suspend fun getProject(id: String): ApiResult<ProjectDetail> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.project(id) }
            if (!res.success || res.project == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "PROJECT_NOT_FOUND", res.error?.message ?: "Not found")
            }
            val jsonStr = res.json?.toString()
            cache.upsert(CachedProject(res.project.id, res.project.name, res.project.gameType, res.project.rev, jsonStr, System.currentTimeMillis(), false))
            ApiResult.Success(ProjectDetail(res.project, res.json))
        } catch (t: Throwable) {
            val c = cache.get(id)
            if (c?.json != null) {
                val obj = try {
                    NetworkModule.json.parseToJsonElement(c.json) as? JsonObject
                } catch (_: Exception) {
                    null
                }
                ApiResult.Success(ProjectDetail(ProjectDto(c.id, c.name, c.gameType, null, c.rev), obj))
            } else {
                val e = ApiErrors.map(t)
                ApiResult.Error(e.code, e.message ?: "Failed", e.httpCode)
            }
        }
    }

    suspend fun saveProject(id: String, baseRev: Int, json: JsonObject, name: String? = null): SaveResult = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.saveProject(id, SaveProjectRequest(baseRev, name, json)) }
            if (res.success && res.rev != null) {
                cache.upsert(CachedProject(id, name ?: (cache.get(id)?.name ?: ""), cache.get(id)?.gameType ?: "", res.rev, json.toString(), System.currentTimeMillis(), false))
                SaveResult.Saved(res.rev)
            } else {
                SaveResult.Failed(res.error?.code ?: "INTERNAL", res.error?.message ?: "Save failed")
            }
        } catch (t: Throwable) {
            if (t is HttpException && t.code() == 409) {
                val parsed = try {
                    ApiErrors.rawBody(t)?.let { NetworkModule.json.decodeFromString<ConflictResponse>(it) }
                } catch (_: Exception) {
                    null
                }
                return@withContext SaveResult.Conflict(parsed?.serverRev ?: -1, parsed?.serverCopy)
            }
            val e = ApiErrors.map(t)
            if (e.code == "REVISION_CONFLICT") return@withContext SaveResult.Conflict(-1, null)
            if (e.httpCode == 0) {
                // Offline: keep the JSON in the dirty cache; SyncWorker pushes it later.
                val c = cache.get(id)
                cache.upsert(
                    CachedProject(
                        id, name ?: (c?.name ?: ""), c?.gameType ?: "", baseRev,
                        json.toString(), System.currentTimeMillis(), true,
                    ),
                )
                return@withContext SaveResult.OfflineCached
            }
            SaveResult.Failed(e.code, e.message ?: "Save failed")
        }
    }

    /** Three-way merge on the server. Throws [ApiException] on failure. */
    suspend fun mergeProject(id: String, baseRev: Int, json: JsonObject): MergedProject = withContext(Dispatchers.IO) {
        val res = auth.withAuthRetry { api.merge(id, MergeRequest(baseRev, json)) }
        val merged = res.merged
        if (!res.success || merged == null) {
            throw ApiException(res.error?.code ?: "MERGE_FAILED", res.error?.message ?: "Merge failed", 0)
        }
        MergedProject(merged, res.conflicts, res.serverRev ?: (baseRev + 1))
    }

    suspend fun revisions(id: String): List<RevisionDto> = withContext(Dispatchers.IO) {
        auth.withAuthRetry { api.revisions(id) }.revisions
    }

    /** Restores [rev] as a new head revision. Returns the new rev. Throws [ApiException] on failure. */
    suspend fun restoreRevision(id: String, rev: Int, baseRev: Int): Int = withContext(Dispatchers.IO) {
        val res = auth.withAuthRetry { api.restore(id, RestoreRequest(rev, baseRev)) }
        if (!res.success || res.rev == null) {
            throw ApiException(res.error?.code ?: "RESTORE_FAILED", res.error?.message ?: "Restore failed", 0)
        }
        res.rev
    }

    suspend fun deleteProject(id: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            auth.withAuthRetry { api.deleteProject(id) }
            cache.delete(id)
            ApiResult.Success(Unit)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "Delete failed", e.httpCode)
        }
    }

    /** Game types from server registries; static Tier-1 fallback when offline. */
    suspend fun getGameTypes(): List<GameTypeDto> = withContext(Dispatchers.IO) {
        try {
            val res = api.registries()
            if (res.success && res.gameTypes.isNotEmpty()) return@withContext res.gameTypes
        } catch (_: Throwable) {
        }
        FALLBACK_TIER1
    }

    companion object {
        val FALLBACK_TIER1 = listOf(
            GameTypeDto("puzzle", "Puzzle", 1, orientation = "portrait", description = ""),
            GameTypeDto("quiz", "Quiz", 1, orientation = "portrait", description = ""),
            GameTypeDto("word", "Word Game", 1, orientation = "portrait", description = ""),
            GameTypeDto("arcade", "Arcade", 1, orientation = "portrait", description = ""),
            GameTypeDto("runner", "Runner", 1, orientation = "landscape", description = ""),
            GameTypeDto("platformer", "Platformer", 1, orientation = "landscape", description = ""),
            GameTypeDto("match3", "Match-3", 1, orientation = "portrait", description = ""),
            GameTypeDto("card", "Card Game", 1, orientation = "portrait", description = ""),
        )
    }
}
