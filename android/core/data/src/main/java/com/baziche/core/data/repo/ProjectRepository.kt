package com.baziche.core.data.repo

import com.baziche.core.common.ApiResult
import com.baziche.core.data.db.CachedProject
import com.baziche.core.data.db.ProjectCache
import com.baziche.core.network.ApiErrors
import com.baziche.core.network.ApiException
import com.baziche.core.network.BazicheApi
import com.baziche.core.network.BuildDto
import com.baziche.core.network.ConflictResponse
import com.baziche.core.network.CreateBuildRequest
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
            val project = res.project
            if (!res.success || project == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", res.error?.message ?: "Create failed")
            }
            cache.upsert(CachedProject(project.id, project.name, project.gameType, project.rev, null, System.currentTimeMillis(), false))
            ApiResult.Success(project)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "Create failed", e.httpCode)
        }
    }

    suspend fun getProject(id: String): ApiResult<ProjectDetail> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.project(id) }
            val project = res.project
            if (!res.success || project == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "PROJECT_NOT_FOUND", res.error?.message ?: "Not found")
            }
            val jsonStr = res.json?.toString()
            cache.upsert(CachedProject(project.id, project.name, project.gameType, project.rev, jsonStr, System.currentTimeMillis(), false))
            ApiResult.Success(ProjectDetail(project, res.json))
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
            val rev = res.rev
            if (res.success && rev != null) {
                cache.upsert(CachedProject(id, name ?: (cache.get(id)?.name ?: ""), cache.get(id)?.gameType ?: "", rev, json.toString(), System.currentTimeMillis(), false))
                SaveResult.Saved(rev)
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

    suspend fun restoreRevision(id: String, rev: Int, baseRev: Int): Int = withContext(Dispatchers.IO) {
        val res = auth.withAuthRetry { api.restore(id, RestoreRequest(rev, baseRev)) }
        val newRev = res.rev
        if (!res.success || newRev == null) {
            throw ApiException(res.error?.code ?: "RESTORE_FAILED", res.error?.message ?: "Restore failed", 0)
        }
        newRev
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

    // ---------- Build & Export Methods ----------

    suspend fun createBuild(projectId: String, target: String = "apk"): ApiResult<BuildDto> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.createBuild(CreateBuildRequest(projectId, target)) }
            val b = res.build
            if (!res.success || b == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "BUILD_FAILED", res.error?.message ?: "شروع بیلد با خطا مواجه شد")
            }
            ApiResult.Success(b)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "خطا در برقراری ارتباط برای ساخت بیلد", e.httpCode)
        }
    }

    suspend fun listBuilds(): ApiResult<List<BuildDto>> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.builds() }
            if (!res.success) {
                return@withContext ApiResult.Error(res.error?.code ?: "INTERNAL", res.error?.message ?: "دریافت فهرست بیلدها ناموفق بود")
            }
            ApiResult.Success(res.builds)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "خطا در اتصال به سرور", e.httpCode)
        }
    }

    suspend fun getBuild(buildId: String): ApiResult<BuildDto> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.buildDetail(buildId) }
            val b = res.build
            if (!res.success || b == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "BUILD_NOT_FOUND", res.error?.message ?: "بیلد یافت نشد")
            }
            ApiResult.Success(b)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "خطا در دریافت وضعیت بیلد", e.httpCode)
        }
    }

    /** Game types from server registries; comprehensive 20-game-type fallback when offline. */
    suspend fun getGameTypes(): List<GameTypeDto> = withContext(Dispatchers.IO) {
        try {
            val res = api.registries()
            if (res.success && res.gameTypes.isNotEmpty()) return@withContext res.gameTypes
        } catch (_: Throwable) {
        }
        FALLBACK_ALL_20
    }

    companion object {
        val FALLBACK_ALL_20 = listOf(
            GameTypeDto("puzzle", "Puzzle", 1, orientation = "portrait", description = "Tile puzzles & boards", nameFa = "معمایی و جورچین", descriptionFa = "پازل‌های چیدمانی و حل معما"),
            GameTypeDto("quiz", "Quiz", 1, orientation = "portrait", description = "Trivia Q&A", nameFa = "کوئیز و دانستنی‌ها", descriptionFa = "مسابقه هوش و چهارگزینه‌ای"),
            GameTypeDto("word", "Word Game", 1, orientation = "portrait", description = "Word scramble & letters", nameFa = "کلمات و حدس واژه", descriptionFa = "حدس کلمات و جدول فارسی"),
            GameTypeDto("arcade", "Arcade", 1, orientation = "portrait", description = "Action & high score", nameFa = "آرکید و رکوردی", descriptionFa = "هیجانی و رکوردی سریع"),
            GameTypeDto("runner", "Runner", 1, orientation = "landscape", description = "Endless run & dodge", nameFa = "دونده بی‌پایان", descriptionFa = "دویدن و پرش از روی موانع"),
            GameTypeDto("platformer", "Platformer", 1, orientation = "landscape", description = "Jump & run levels", nameFa = "سکوبازی و پرش", descriptionFa = "پرش میان سکوها و گذر از مراحل"),
            GameTypeDto("match3", "Match-3", 1, orientation = "portrait", description = "Match 3 tiles", nameFa = "تطبیق سه تایی", descriptionFa = "اتصال و حذف مهره‌های هم‌رنگ"),
            GameTypeDto("card", "Card Game", 1, orientation = "portrait", description = "Turn-based cards", nameFa = "کارتی و پاسور", descriptionFa = "دست‌های کارت و بازی‌های کارتی"),
            GameTypeDto("board", "Board Game", 2, orientation = "landscape", description = "Turn-based boards", nameFa = "تخته و منچ", descriptionFa = "بازی‌های نوبتی و پرتاب تاس"),
            GameTypeDto("towerdefense", "Tower Defense", 2, orientation = "landscape", description = "Towers & waves", nameFa = "دفاع از قلعه", descriptionFa = "ساخت برج‌های دفاعی"),
            GameTypeDto("racing", "Racing", 2, orientation = "landscape", description = "Speed & racing tracks", nameFa = "ماشینی و مسابقه‌ای", descriptionFa = "سرعت، سبقت و مسابقه اتومبیل"),
            GameTypeDto("shooter", "Shooter", 2, orientation = "landscape", description = "Target & projectile", nameFa = "شوتر و تیراندازی", descriptionFa = "شلیک به هدف‌ها و دفاع موشکی"),
            GameTypeDto("adventure", "Adventure", 2, orientation = "landscape", description = "Story & quest", nameFa = "ماجراجویی و داستانی", descriptionFa = "کاوش در دنیاها و مکالمه با شخصیت‌ها"),
            GameTypeDto("idle", "Idle Game", 2, orientation = "portrait", description = "Incremental clicker", nameFa = "آیدل و کلیکی", descriptionFa = "کلیک، ارتقا و تولید ثروت"),
            GameTypeDto("tycoon", "Tycoon", 2, orientation = "landscape", description = "Business simulation", nameFa = "سرمایه‌داری و مدیریت", descriptionFa = "مدیریت کسب‌وکار و رشد شرکت"),
            GameTypeDto("merge", "Merge Game", 2, orientation = "portrait", description = "Merge & level up", nameFa = "ترکیب و ادغام", descriptionFa = "ترکیب آیتم‌های مشابه و ارتقا"),
            GameTypeDto("survival", "Survival", 3, orientation = "landscape", description = "Gather & craft", nameFa = "بقا و نجات", descriptionFa = "جمع‌آوری منابع و زنده ماندن"),
            GameTypeDto("detective", "Detective", 3, orientation = "landscape", description = "Find clues & mystery", nameFa = "کارآگاهی و اشیاء مخفی", descriptionFa = "پیدا کردن سرنخ‌ها و حل پرونده‌ها"),
            GameTypeDto("zombie", "Zombie Defense", 3, orientation = "landscape", description = "Defend against undead", nameFa = "دفاع در برابر زامبی‌ها", descriptionFa = "سنگربندی و مقابله با زامبی‌ها"),
            GameTypeDto("minigames", "Mini Games", 3, orientation = "portrait", description = "Party mini challenges", nameFa = "مینی‌گیم‌های متنوع", descriptionFa = "مجموعه‌ای از بازی‌های کوچک و جذاب"),
        )
        val FALLBACK_TIER1 = FALLBACK_ALL_20.filter { it.tier == 1 }
    }
}
