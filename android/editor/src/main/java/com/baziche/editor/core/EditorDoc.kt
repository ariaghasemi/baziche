package com.baziche.editor.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * In-memory editor for the Baziche project-JSON format (§7 of the architecture doc).
 * Owns a mutable [JsonObject] root; every mutation returns success/failure so the
 * ViewModel can refresh UI state. Pure JVM logic — unit-tested, no Android APIs.
 */
data class SceneItem(val id: String, val name: String, val entry: Boolean)

data class ObjTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 100f,
    val h: Float = 100f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
)

data class ObjItem(
    val id: String,
    val sceneId: String,
    val kind: String,
    val transform: ObjTransform,
    val visible: Boolean = true,
    val layer: Int = 0,
)

private fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().take(8)}"

class EditorDoc(root: JsonObject) {
    private var root: JsonObject = root

    fun toJson(): JsonObject = root

    // ---------- low-level helpers ----------
    private fun array(key: String): List<JsonObject> =
        (root[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun setArray(key: String, items: List<JsonObject>) {
        root = JsonObject(root.toMutableMap().also { it[key] = JsonArray(items) })
    }

    private fun objOf(o: JsonObject, key: String): JsonObject? = o[key] as? JsonObject

    // ---------- meta ----------
    fun orientation(): String =
        (objOf(root, "meta")?.get("orientation")?.jsonPrimitive?.contentOrNull)
            ?: "portrait"

    fun projectName(): String =
        objOf(root, "meta")?.get("name")?.jsonPrimitive?.contentOrNull ?: ""

    // ---------- scenes ----------
    fun scenes(): List<SceneItem> = array("scenes").map {
        SceneItem(
            id = it["id"]!!.jsonPrimitive.content,
            name = it["name"]?.jsonPrimitive?.contentOrNull ?: "",
            entry = it["entry"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun addScene(name: String): SceneItem {
        val all = scenes()
        val item = SceneItem(newId("scene"), name.ifBlank { "Scene ${all.size + 1}" }, entry = all.isEmpty())
        val el = buildJsonObject {
            put("id", JsonPrimitive(item.id))
            put("name", JsonPrimitive(item.name))
            put("entry", JsonPrimitive(item.entry))
            put("background", buildJsonObject { put("color", JsonPrimitive("#121212")) })
            put("objectIds", JsonArray(emptyList()))
            put("transitions", JsonArray(emptyList()))
        }
        setArray("scenes", array("scenes") + el)
        return item
    }

    fun renameScene(id: String, name: String): Boolean {
        if (name.isBlank()) return false
        val list = array("scenes")
        if (list.none { it["id"]?.jsonPrimitive?.content == id }) return false
        setArray("scenes", list.map {
            if (it["id"]?.jsonPrimitive?.content == id)
                JsonObject(it.toMutableMap().also { m -> m["name"] = JsonPrimitive(name) })
            else it
        })
        return true
    }

    /** Deletes a scene; keeps at least one scene alive. Cascades to its objects. */
    fun deleteScene(id: String): Boolean {
        var list = array("scenes")
        if (list.size <= 1 || list.none { it["id"]?.jsonPrimitive?.content == id }) return false
        list = list.filter { it["id"]?.jsonPrimitive?.content != id }
        val wasEntry = list.none { it["entry"]?.jsonPrimitive?.booleanOrNull == true }
        if (wasEntry) {
            list = list.mapIndexed { i, o ->
                if (i == 0) JsonObject(o.toMutableMap().also { it["entry"] = JsonPrimitive(true) }) else o
            }
        }
        setArray("scenes", list)
        setArray("objects", array("objects").filter { it["sceneId"]?.jsonPrimitive?.content != id })
        return true
    }

    fun duplicateScene(id: String): SceneItem? {
        val src = array("scenes").firstOrNull { it["id"]?.jsonPrimitive?.content == id } ?: return null
        val item = SceneItem(newId("scene"), "${src["name"]?.jsonPrimitive?.contentOrNull} copy", entry = false)
        val el = buildJsonObject {
            put("id", JsonPrimitive(item.id))
            put("name", JsonPrimitive(item.name))
            put("entry", JsonPrimitive(false))
            put("background", src["background"] ?: buildJsonObject { put("color", JsonPrimitive("#121212")) })
            put("objectIds", JsonArray(emptyList()))
            put("transitions", JsonArray(emptyList()))
        }
        setArray("scenes", array("scenes") + el)
        return item
    }

    fun setEntry(id: String): Boolean {
        val list = array("scenes")
        if (list.none { it["id"]?.jsonPrimitive?.content == id }) return false
        setArray("scenes", list.map {
            JsonObject(it.toMutableMap().also { m ->
                m["entry"] = JsonPrimitive(it["id"]?.jsonPrimitive?.content == id)
            })
        })
        return true
    }

    /** Moves a scene left (-1) or right (+1) in the list. */
    fun moveScene(id: String, dir: Int): Boolean {
        val list = array("scenes").toMutableList()
        val i = list.indexOfFirst { it["id"]?.jsonPrimitive?.content == id }
        val j = i + dir
        if (i < 0 || j !in list.indices) return false
        val t = list[i]; list[i] = list[j]; list[j] = t
        setArray("scenes", list)
        return true
    }

    // ---------- objects ----------
    private fun parseTransform(o: JsonObject): ObjTransform {
        val t = objOf(objOf(o, "components") ?: JsonObject(emptyMap()), "Transform")
        fun f(e: JsonElement?): Float = e?.jsonPrimitive?.floatOrNull ?: e?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f
        return ObjTransform(
            x = f(t?.get("x")),
            y = f(t?.get("y")),
            w = t?.get("w")?.jsonPrimitive?.floatOrNull ?: t?.get("w")?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 100f,
            h = t?.get("h")?.jsonPrimitive?.floatOrNull ?: t?.get("h")?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 100f,
            rotation = f(t?.get("rotation")),
            opacity = t?.get("opacity")?.jsonPrimitive?.floatOrNull
                ?: t?.get("opacity")?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1f,
        )
    }

    fun objectsIn(sceneId: String): List<ObjItem> =
        array("objects").filter { it["sceneId"]?.jsonPrimitive?.content == sceneId }.map {
            ObjItem(
                id = it["id"]!!.jsonPrimitive.content,
                sceneId = sceneId,
                kind = it["kind"]?.jsonPrimitive?.contentOrNull ?: "rect",
                transform = parseTransform(it),
                visible = it["visible"]?.jsonPrimitive?.booleanOrNull ?: true,
                layer = it["layer"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.sortedBy { o -> o.layer }

    fun getObject(id: String): ObjItem? =
        array("objects").firstOrNull { it["id"]?.jsonPrimitive?.content == id }?.let {
            val sceneId = it["sceneId"]?.jsonPrimitive?.contentOrNull ?: return@let null
            ObjItem(
                id = id, sceneId = sceneId,
                kind = it["kind"]?.jsonPrimitive?.contentOrNull ?: "rect",
                transform = parseTransform(it),
                visible = it["visible"]?.jsonPrimitive?.booleanOrNull ?: true,
                layer = it["layer"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }

    fun addObject(sceneId: String, kind: String, x: Float, y: Float): ObjItem? {
        if (scenes().none { it.id == sceneId }) return null
        val existing = objectsIn(sceneId)
        val t = when (kind) {
            "text", "button" -> ObjTransform(x = x, y = y, w = 200f, h = 64f)
            "panel" -> ObjTransform(x = x, y = y, w = 320f, h = 240f)
            else -> ObjTransform(x = x, y = y, w = 120f, h = 120f)
        }
        val obj = ObjItem(newId("obj"), sceneId, kind, t, visible = true, layer = (existing.maxOfOrNull { it.layer } ?: -1) + 1)
        setArray("objects", array("objects") + objToJson(obj))
        return obj
    }

    private fun objToJson(o: ObjItem): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(o.id))
        put("sceneId", JsonPrimitive(o.sceneId))
        put("kind", JsonPrimitive(o.kind))
        put("visible", JsonPrimitive(o.visible))
        put("layer", JsonPrimitive(o.layer))
        put("components", buildJsonObject {
            put("Transform", buildJsonObject {
                put("x", JsonPrimitive(o.transform.x))
                put("y", JsonPrimitive(o.transform.y))
                put("w", JsonPrimitive(o.transform.w))
                put("h", JsonPrimitive(o.transform.h))
                put("rotation", JsonPrimitive(o.transform.rotation))
                put("opacity", JsonPrimitive(o.transform.opacity))
            })
        })
    }

    private fun mutateObject(id: String, fn: (MutableMap<String, JsonElement>) -> Unit): Boolean {
        val list = array("objects")
        if (list.none { it["id"]?.jsonPrimitive?.content == id }) return false
        setArray("objects", list.map {
            if (it["id"]?.jsonPrimitive?.content == id) JsonObject(it.toMutableMap().also(fn)) else it
        })
        return true
    }

    fun updateTransform(id: String, t: ObjTransform): Boolean = mutateObject(id) { m ->
        val comps = (m["components"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val tr = ((comps["Transform"] as? JsonObject)?.toMutableMap() ?: mutableMapOf())
        tr["x"] = JsonPrimitive(t.x); tr["y"] = JsonPrimitive(t.y)
        tr["w"] = JsonPrimitive(t.w); tr["h"] = JsonPrimitive(t.h)
        tr["rotation"] = JsonPrimitive(t.rotation); tr["opacity"] = JsonPrimitive(t.opacity)
        comps["Transform"] = JsonObject(tr)
        m["components"] = JsonObject(comps)
    }

    fun updateObject(id: String, visible: Boolean? = null, layer: Int? = null): Boolean = mutateObject(id) { m ->
        if (visible != null) m["visible"] = JsonPrimitive(visible)
        if (layer != null) m["layer"] = JsonPrimitive(layer)
    }

    fun deleteObject(id: String): Boolean {
        val list = array("objects")
        if (list.none { it["id"]?.jsonPrimitive?.content == id }) return false
        setArray("objects", list.filter { it["id"]?.jsonPrimitive?.content != id })
        return true
    }
}
