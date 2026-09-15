package com.baziche.core.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Session(val userId: String, val username: String, val phone: String, val accessToken: String, val refreshToken: String)

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/** Persisted session + in-memory token cache for the network interceptor (no blocking IO). */
interface SessionStore {
    val sessionFlow: Flow<Session?>
    fun cachedAccessToken(): String?
    suspend fun save(session: Session)
    suspend fun updateTokens(accessToken: String, refreshToken: String)
    suspend fun clear()
}

class DataStoreSessionStore(context: Context) : SessionStore {
    private val app = context.applicationContext
    private val ds: DataStore<Preferences> get() = app.sessionDataStore

    @Volatile
    private var cached: Session? = null

    override val sessionFlow: Flow<Session?> = ds.data.map { p ->
        val access = p[Keys.ACCESS]
        val refresh = p[Keys.REFRESH]
        val uid = p[Keys.USER_ID]
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty() || uid.isNullOrEmpty()) null
        else Session(uid, p[Keys.USERNAME].orEmpty(), p[Keys.PHONE].orEmpty(), access, refresh)
    }

    override fun cachedAccessToken(): String? = cached?.accessToken

    override suspend fun save(session: Session) {
        ds.edit { e ->
            e[Keys.USER_ID] = session.userId
            e[Keys.USERNAME] = session.username
            e[Keys.PHONE] = session.phone
            e[Keys.ACCESS] = session.accessToken
            e[Keys.REFRESH] = session.refreshToken
        }
        cached = session
    }

    override suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val cur = cached ?: sessionFlow.first()
        if (cur != null) save(cur.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    override suspend fun clear() {
        ds.edit { it.clear() }
        cached = null
    }

    /** Populate the in-memory cache from disk (call at startup). Returns the session if any. */
    suspend fun load(): Session? {
        val s = sessionFlow.first()
        cached = s
        return s
    }

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val PHONE = stringPreferencesKey("phone")
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
    }
}
