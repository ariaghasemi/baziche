package com.baziche.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LoadedProject(
    val name: String,
    val gameType: String,
    val orientation: String,
    val scenes: List<RtScene>,
    val objects: List<RtObject>,
    val events: List<RtEvent>,
    val varDefs: List<RtVarDef>,
    val assetIds: Set<String>,
    val levels: List<RtLevel>,
)

/**
 * Parses project JSON (shared/project-format.schema.json v1) into runtime entities.
 * Lenient: broken entries are skipped, never crash. Strict only on formatVersion.
 */
object ProjectLoader {
    const val SUPPORTED_FORMAT = 1

    fun load(root: JsonObject): LoadedProject {
        val v = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
        if (v != SUPPORTED_FORMAT) {
            throw FormatException("UNSUPPORTED_FORMAT: engine supports v$SUPPORTED_FORMAT, got ${v ?: "?"}")
        }
        val meta = root.obj("meta") ?: JsonObject(emptyMap())
        return LoadedProject(
            name = meta.str("name", "Game"),
            gameType = meta.str("gameType", "arcade"),
            orientation = meta.str("orientation", "portrait"),
            scenes = root.arr("scenes").mapNotNull(::parseScene),
            objects = root.arr("objects").mapNotNull(::parseObject),
            events = root.arr("events").mapNotNull(::parseEvent),
            varDefs = root.arr("variables").mapNotNull(::parseVar),
            assetIds = root.arr("assets").mapNotNull {
                (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { id -> id.isNotBlank() }
            }.toSet(),
            levels = root.arr("levels").mapNotNull {
                val o = it as? JsonObject ?: return@mapNotNull null
                val id = o.str("id")
                if (id.isBlank()) null else RtLevel(id, o.str("name", id))
            },
        )
    }

    private fun parseScene(el: JsonElement): RtScene? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id")
        if (id.isBlank()) return null
        return RtScene(id, o.str("name", id), o.bool("entry"), o.obj("background") ?: JsonObject(emptyMap()))
    }

    private fun parseObject(el: JsonElement): RtObject? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id")
        val sceneId = o.str("sceneId")
        if (id.isBlank() || sceneId.isBlank()) return null
        val comps = mutableMapOf<String, JsonObject>()
        (o["components"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonObject)?.let { comps[k] = it } }
        return RtObject(
            id = id,
            sceneId = sceneId,
            kind = o.str("kind", "rect").ifBlank { "rect" },
            visible = o.bool("visible", true),
            layer = o.num("layer").toInt(),
            components = comps,
        )
    }

    private fun parseEvent(el: JsonElement): RtEvent? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id")
        val trigger = o.obj("trigger")
        if (id.isBlank() || trigger == null) return null
        return RtEvent(
            id = id,
            trigger = trigger,
            conditions = (o["conditions"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList(),
            actions = (o["actions"] as? JsonArray)?.mapNotNull { RtAction.parse(it) } ?: emptyList(),
        )
    }

    private fun parseVar(el: JsonElement): RtVarDef? {
        val o = el as? JsonObject ?: return null
        val name = o.str("name")
        if (name.isBlank()) return null
        val type = when (o.str("type")) {
            "number" -> VarType.NUMBER
            "string" -> VarType.STRING
            "bool" -> VarType.BOOL
            else -> return null
        }
        return RtVarDef(name, type, VariableStore.coerce(type, o["initial"] ?: JsonPrimitive(0)), o.bool("persist"))
    }
}
