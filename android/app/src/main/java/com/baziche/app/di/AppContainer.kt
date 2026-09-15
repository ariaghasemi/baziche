package com.baziche.app.di

import android.content.Context
import android.os.Build
import com.baziche.app.ui.util.LanguageManager
import com.baziche.core.data.db.BazicheDb
import com.baziche.core.data.db.SqlProjectCache
import com.baziche.core.data.repo.AuthRepository
import com.baziche.core.data.repo.ProjectRepository
import com.baziche.core.data.session.DataStoreSessionStore
import com.baziche.core.network.NetworkModule

/** Manual DI (Phase 1). No codegen, no magic — Hilt/Dagger only if Phase 2+ needs it. */
class AppContainer(context: Context, val apiBaseUrl: String, val debug: Boolean) {
    private val app = context.applicationContext

    val languageManager = LanguageManager(app)
    val sessionStore = DataStoreSessionStore(app)

    val api by lazy {
        NetworkModule.create(apiBaseUrl, debug) { sessionStore.cachedAccessToken() }
    }

    private val db by lazy { BazicheDb(app) }
    val projectCache by lazy { SqlProjectCache(db) }

    val authRepository by lazy {
        AuthRepository(api, sessionStore) { "android-${Build.MODEL ?: "device"}" }
    }
    val projectRepository by lazy { ProjectRepository(api, authRepository, projectCache) }
}
