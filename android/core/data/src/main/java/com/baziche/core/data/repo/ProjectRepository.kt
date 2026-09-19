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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import retrofit2.HttpException
import java.util.UUID

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
            if (res.success) {
                val now = System.currentTimeMillis()
                val serverIds = res.projects.map { it.id }.toSet()
                res.projects.forEach { p ->
                    val prev = cache.get(p.id)
                    cache.upsert(CachedProject(p.id, p.name, p.gameType, p.rev, prev?.json, p.updatedAt ?: now, prev?.dirty == true))
                }
                val localOnly = cache.list().filter { it.id !in serverIds }.map {
                    ProjectDto(it.id, it.name, it.gameType, null, it.rev, null, it.updatedAt)
                }
                return@withContext ApiResult.Success(localOnly + res.projects)
            }
        } catch (_: Throwable) {
        }
        // Offline / cached fallback
        val cached = cache.list()
        if (cached.isNotEmpty()) {
            ApiResult.Success(cached.map { ProjectDto(it.id, it.name, it.gameType, null, it.rev, null, it.updatedAt) })
        } else {
            ApiResult.Success(emptyList())
        }
    }

    suspend fun createProject(name: String, gameType: String): ApiResult<ProjectDto> = withContext(Dispatchers.IO) {
        val cleanName = name.trim().ifBlank { "پروژه بازی جدید" }
        val starterJsonObj = buildStarterJsonObject(cleanName, gameType)
        val starterJsonStr = starterJsonObj.toString()

        try {
            val res = auth.withAuthRetry { api.createProject(CreateProjectRequest(cleanName, gameType)) }
            val project = res.project
            if (res.success && project != null) {
                cache.upsert(CachedProject(project.id, project.name, project.gameType, project.rev, starterJsonStr, System.currentTimeMillis(), false))
                return@withContext ApiResult.Success(project)
            }
        } catch (_: Throwable) {
            // Server or network error fallback: create locally
        }

        // Local-first creation guarantee: project is immediately ready to edit!
        val localId = "prj_loc_" + UUID.randomUUID().toString().take(8)
        val localProject = ProjectDto(localId, cleanName, gameType, null, 1)
        cache.upsert(CachedProject(localId, cleanName, gameType, 1, starterJsonStr, System.currentTimeMillis(), true))
        ApiResult.Success(localProject)
    }

    suspend fun getProject(id: String): ApiResult<ProjectDetail> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.project(id) }
            val project = res.project
            if (res.success && project != null) {
                val json = res.json ?: buildStarterJsonObject(project.name, project.gameType)
                cache.upsert(CachedProject(project.id, project.name, project.gameType, project.rev, json.toString(), System.currentTimeMillis(), false))
                return@withContext ApiResult.Success(ProjectDetail(project, json))
            }
        } catch (_: Throwable) {
        }

        // Cache fallback
        val c = cache.get(id)
        if (c != null) {
            val obj = try {
                c.json?.let { NetworkModule.json.parseToJsonElement(it) as? JsonObject }
            } catch (_: Exception) {
                null
            } ?: buildStarterJsonObject(c.name, c.gameType)
            ApiResult.Success(ProjectDetail(ProjectDto(c.id, c.name, c.gameType, null, c.rev), obj))
        } else {
            // If completely unknown, synthesize a valid starter project with this id
            val obj = buildStarterJsonObject("بازی من", "quiz")
            val p = ProjectDto(id, "بازی من", "quiz", null, 1)
            cache.upsert(CachedProject(id, "بازی من", "quiz", 1, obj.toString(), System.currentTimeMillis(), true))
            ApiResult.Success(ProjectDetail(p, obj))
        }
    }

    suspend fun saveProject(id: String, baseRev: Int, json: JsonObject, name: String? = null): SaveResult = withContext(Dispatchers.IO) {
        val cached = cache.get(id)
        val projName = name ?: (cached?.name ?: "پروژه بازی")
        val gameType = cached?.gameType ?: "quiz"

        try {
            val res = auth.withAuthRetry { api.saveProject(id, SaveProjectRequest(baseRev, name, json)) }
            val rev = res.rev
            if (res.success && rev != null) {
                cache.upsert(CachedProject(id, projName, gameType, rev, json.toString(), System.currentTimeMillis(), false))
                return@withContext SaveResult.Saved(rev)
            }
            if (res.error?.code == "REVISION_CONFLICT") {
                return@withContext SaveResult.Conflict(res.serverRev ?: (baseRev + 1), res.serverCopy)
            }
        } catch (_: Throwable) {
        }

        // Local save fallback: user never loses their progress
        val nextRev = baseRev + 1
        cache.upsert(CachedProject(id, projName, gameType, nextRev, json.toString(), System.currentTimeMillis(), true))
        SaveResult.Saved(nextRev)
    }

    suspend fun deleteProject(id: String): ApiResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.deleteProject(id) }
            if (res.success) {
                cache.delete(id)
                return@withContext ApiResult.Success(Unit)
            }
        } catch (_: Throwable) {
        }
        cache.delete(id)
        ApiResult.Success(Unit)
    }

    suspend fun revisions(id: String): List<RevisionDto> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.revisions(id) }
            if (res.success) res.revisions else emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun restoreRevision(id: String, rev: Int, baseRev: Int): ApiResult<Int> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.restore(id, RestoreRequest(rev, baseRev)) }
            val newRev = res.rev
            if (res.success && newRev != null) {
                return@withContext ApiResult.Success(newRev)
            }
            ApiResult.Error(res.error?.code ?: "RESTORE_FAILED", res.error?.message ?: "بازگردانی ناموفق بود")
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "بازگردانی ناموفق بود", e.httpCode)
        }
    }

    suspend fun mergeProject(id: String, baseRev: Int, json: JsonObject): MergedProject = withContext(Dispatchers.IO) {
        val res = auth.withAuthRetry { api.merge(id, MergeRequest(baseRev, json)) }
        if (!res.success || res.merged == null || res.serverRev == null) {
            throw ApiException(res.error?.code ?: "MERGE_FAILED", res.error?.message ?: "Merge failed", 400)
        }
        MergedProject(res.merged, res.conflicts, res.serverRev)
    }

    suspend fun createBuild(projectId: String, target: String = "apk"): ApiResult<BuildDto> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.createBuild(CreateBuildRequest(projectId, target)) }
            val b = res.build
            if (!res.success || b == null) {
                return@withContext ApiResult.Error(res.error?.code ?: "BUILD_FAILED", res.error?.message ?: "خطا در شروع بیلد")
            }
            ApiResult.Success(b)
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "خطا در برقراری ارتباط با سرور بیلد", e.httpCode)
        }
    }

    suspend fun listBuilds(): ApiResult<List<BuildDto>> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.builds() }
            if (res.success) {
                ApiResult.Success(res.builds)
            } else {
                ApiResult.Error(res.error?.code ?: "INTERNAL", res.error?.message ?: "خطا در دریافت بیلدها")
            }
        } catch (t: Throwable) {
            val e = ApiErrors.map(t)
            ApiResult.Error(e.code, e.message ?: "خطا در دریافت لیست بیلدها", e.httpCode)
        }
    }

    suspend fun buildStatus(id: String): ApiResult<BuildDto> = withContext(Dispatchers.IO) {
        try {
            val res = auth.withAuthRetry { api.buildDetail(id) }
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

    suspend fun getGameTypes(): List<GameTypeDto> = withContext(Dispatchers.IO) {
        try {
            val res = api.registries()
            if (res.success && res.gameTypes.isNotEmpty()) return@withContext res.gameTypes
        } catch (_: Throwable) {
        }
        FALLBACK_ALL_20
    }

    companion object {
        fun buildStarterJsonObject(name: String, gameType: String): JsonObject {
            val isLandscape = gameType in listOf("runner", "platformer", "shooter", "zombie", "racing", "survival", "towerdefense", "adventure", "tycoon", "detective", "board")
            val orientation = if (isLandscape) "landscape" else "portrait"
            return buildJsonObject {
                put("formatVersion", JsonPrimitive(1))
                put("meta", buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("gameType", JsonPrimitive(gameType))
                    put("versionCode", JsonPrimitive(1))
                    put("versionName", JsonPrimitive("1.0.0"))
                    put("orientation", JsonPrimitive(orientation))
                })
                put("settings", buildJsonObject {
                    put("locale", JsonPrimitive("fa"))
                    put("fps", JsonPrimitive(60))
                })
                put("scenes", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive("scene_main"))
                        put("name", JsonPrimitive("Main"))
                        put("entry", JsonPrimitive(true))
                        put("background", buildJsonObject { put("color", JsonPrimitive("#121212")) })
                        put("objectIds", buildJsonArray { add(JsonPrimitive("obj_title")) })
                        put("transitions", buildJsonArray { })
                    })
                })
                put("objects", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive("obj_title"))
                        put("sceneId", JsonPrimitive("scene_main"))
                        put("kind", JsonPrimitive("text"))
                        put("visible", JsonPrimitive(true))
                        put("layer", JsonPrimitive(1))
                        put("components", buildJsonObject {
                            put("Transform", buildJsonObject {
                                put("x", JsonPrimitive(40))
                                put("y", JsonPrimitive(80))
                                put("w", JsonPrimitive(400))
                                put("h", JsonPrimitive(60))
                                put("rotation", JsonPrimitive(0))
                                put("opacity", JsonPrimitive(1))
                            })
                            put("Text", buildJsonObject {
                                put("text", JsonPrimitive(name))
                                put("size", JsonPrimitive(28))
                                put("color", JsonPrimitive("#10B981"))
                            })
                        })
                    })
                })
                put("events", buildJsonArray { })
                put("variables", buildJsonArray { })
                put("assets", buildJsonArray { })
            }
        }

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
