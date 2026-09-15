package com.baziche.core.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Phase 1 local cache. ARCHITECTURE NOTE: plain SQLiteOpenHelper is used instead of Room
 * because neither KSP (no release for Kotlin 2.4.20 yet) nor kapt (removal risk) is safe
 * right now. The [ProjectCache] interface keeps the door open for a Room migration in
 * Phase 2 with zero changes to repositories or UI.
 */
data class CachedProject(
    val id: String,
    val name: String,
    val gameType: String,
    val rev: Int,
    val json: String?,
    val updatedAt: Long,
    val dirty: Boolean,
)

interface ProjectCache {
    fun upsert(p: CachedProject)
    fun get(id: String): CachedProject?
    fun list(): List<CachedProject>
    fun delete(id: String)
    fun markDirty(id: String, dirty: Boolean)
}

class BazicheDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "baziche.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE cached_projects (
              id TEXT PRIMARY KEY, name TEXT NOT NULL, game_type TEXT NOT NULL,
              rev INTEGER NOT NULL, json TEXT, updated_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL("CREATE INDEX idx_cached_updated ON cached_projects(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: nothing to migrate yet.
    }
}

class SqlProjectCache(private val helper: BazicheDb) : ProjectCache {
    override fun upsert(p: CachedProject) {
        val cv = ContentValues().apply {
            put("id", p.id); put("name", p.name); put("game_type", p.gameType)
            put("rev", p.rev); put("json", p.json); put("updated_at", p.updatedAt)
            put("dirty", if (p.dirty) 1 else 0)
        }
        helper.writableDatabase.insertWithOnConflict("cached_projects", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun get(id: String): CachedProject? {
        helper.readableDatabase.rawQuery("SELECT id,name,game_type,rev,json,updated_at,dirty FROM cached_projects WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            return CachedProject(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getLong(5), c.getInt(6) == 1)
        }
    }

    override fun list(): List<CachedProject> {
        val out = mutableListOf<CachedProject>()
        helper.readableDatabase.rawQuery("SELECT id,name,game_type,rev,json,updated_at,dirty FROM cached_projects ORDER BY updated_at DESC LIMIT 500", null).use { c ->
            while (c.moveToNext()) out += CachedProject(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4), c.getLong(5), c.getInt(6) == 1)
        }
        return out
    }

    override fun delete(id: String) {
        helper.writableDatabase.delete("cached_projects", "id=?", arrayOf(id))
    }

    override fun markDirty(id: String, dirty: Boolean) {
        val cv = ContentValues().apply { put("dirty", if (dirty) 1 else 0) }
        helper.writableDatabase.update("cached_projects", cv, "id=?", arrayOf(id))
    }
}
