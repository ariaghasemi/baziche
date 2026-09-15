package com.baziche.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baziche.app.BazicheApp
import com.baziche.core.data.repo.SaveResult
import com.baziche.core.network.NetworkModule
import kotlinx.serialization.json.jsonObject

/**
 * Background sync: pushes dirty offline-saved projects to the server.
 * Enqueued as unique periodic work (15 min, requires connectivity).
 * Conflicts are left untouched for the user to resolve in the editor.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BazicheApp).container
        val cache = container.projectCache
        val repo = container.projectRepository
        val dirty = try {
            cache.list().filter { it.dirty && it.json != null }
        } catch (_: Exception) {
            return Result.retry()
        }
        if (dirty.isEmpty()) return Result.success()
        var needRetry = false
        for (c in dirty) {
            val json = try {
                NetworkModule.json.parseToJsonElement(c.json!!).jsonObject
            } catch (_: Exception) {
                continue // corrupt cache entry: leave it, don't loop-crash the worker
            }
            when (repo.saveProject(c.id, c.rev, json)) {
                is SaveResult.Saved -> Unit // repo cleared the dirty flag
                is SaveResult.Conflict -> Unit // user resolves in the editor
                is SaveResult.OfflineCached -> needRetry = true
                is SaveResult.Failed -> needRetry = true
            }
        }
        return if (needRetry) Result.retry() else Result.success()
    }
}
