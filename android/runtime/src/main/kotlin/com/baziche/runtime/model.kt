package com.baziche.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Thrown when project JSON cannot be loaded (bad version or broken shape). */
class FormatException(message: String) : IllegalArgumentException(message)

// ---------- lenient JSON reads (schema-validated server-side; runtime never crashes) ----------
internal fun JsonObject.str(key: String, default: String = ""): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: default

internal fun JsonObject.num(key: String, default: Double = 0.0): Double {
    val p = this[key] as? JsonPrimitive ?: return default
    return p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: default
}

internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

// ---------- components (shapes per shared/registries/components.json) ----------
data class RtTransform(
    var x: Float = 0f,
    var y: Float = 0f,
    var w: Float = 100f,
    var h: Float = 100f,
    var rotation: Float = 0f,
    var opacity: Float = 1f,
    var anchorX: Float = 0f,
    var anchorY: Float = 0f,
)

data class RtTextData(
    var text: String = "",
    var fontId: String? = null,
    var size: Float = 24f,
    var color: String = "#FFFFFF",
    var align: String = "center",
    var rtl: Boolean = true,
    var bold: Boolean = false,
)

data class RtSpriteData(
    var assetId: String? = null,
    var tint: String? = null,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
)

data class RtButtonData(
    var label: String = "",
    var eventId: String? = null,
    var enabled: Boolean = true,
)

data class RtHealthData(
    var max: Double = 100.0,
    var current: Double = 100.0,
    var regen: Double = 0.0,
    var invincibleMs: Long = 0L,
)

data class RtAudioData(
    var assetId: String? = null,
    var group: String = "sfx",
    var volume: Float = 1f,
    var loop: Boolean = false,
    var autoplay: Boolean = false,
)

class RtObject(
    val id: String,
    val sceneId: String,
    val kind: String,
    var visible: Boolean,
    var layer: Int,
    val components: MutableMap<String, JsonObject>,
) {
    fun transform(): RtTransform {
        val t = components["Transform"] ?: return RtTransform()
        return RtTransform(
            x = t.num("x").toFloat(), y = t.num("y").toFloat(),
            w = t.num("w", 100.0).toFloat(), h = t.num("h", 100.0).toFloat(),
            rotation = t.num("rotation").toFloat(), opacity = t.num("opacity", 1.0).toFloat(),
            anchorX = t.num("anchorX").toFloat(), anchorY = t.num("anchorY").toFloat(),
        )
    }

    fun setTransform(t: RtTransform) {
        val base = components["Transform"]?.toMutableMap() ?: mutableMapOf()
        base["x"] = JsonPrimitive(t.x); base["y"] = JsonPrimitive(t.y)
        base["w"] = JsonPrimitive(t.w); base["h"] = JsonPrimitive(t.h)
        base["rotation"] = JsonPrimitive(t.rotation); base["opacity"] = JsonPrimitive(t.opacity)
        base["anchorX"] = JsonPrimitive(t.anchorX); base["anchorY"] = JsonPrimitive(t.anchorY)
        components["Transform"] = JsonObject(base)
    }

    fun text(): RtTextData? {
        val o = components["Text"] ?: return null
        return RtTextData(
            text = o.str("text"), fontId = (o["fontId"] as? JsonPrimitive)?.contentOrNull,
            size = o.num("size", 24.0).toFloat(), color = o.str("color", "#FFFFFF"),
            align = o.str("align", "center"), rtl = o.bool("rtl", true), bold = o.bool("bold"),
        )
    }

    fun sprite(): RtSpriteData? {
        val o = components["Sprite"] ?: return null
        return RtSpriteData(
            assetId = (o["assetId"] as? JsonPrimitive)?.contentOrNull,
            tint = (o["tint"] as? JsonPrimitive)?.contentOrNull,
            flipX = o.bool("flipX"), flipY = o.bool("flipY"),
        )
    }

    fun button(): RtButtonData? {
        val o = components["Button"] ?: return null
        return RtButtonData(
            label = o.str("label"),
            eventId = (o["eventId"] as? JsonPrimitive)?.contentOrNull,
            enabled = o.bool("enabled", true),
        )
    }

    fun health(): RtHealthData? {
        val o = components["Health"] ?: return null
        return RtHealthData(
            max = o.num("max", 100.0), current = o.num("current", o.num("max", 100.0)),
            regen = o.num("regen"), invincibleMs = o.num("invincibleMs").toLong(),
        )
    }

    fun setHealth(h: RtHealthData) {
        val base = components["Health"]?.toMutableMap() ?: mutableMapOf()
        base["max"] = JsonPrimitive(h.max); base["current"] = JsonPrimitive(h.current)
        base["regen"] = JsonPrimitive(h.regen); base["invincibleMs"] = JsonPrimitive(h.invincibleMs)
        components["Health"] = JsonObject(base)
    }

    fun audio(): RtAudioData? {
        val o = components["Audio"] ?: return null
        return RtAudioData(
            assetId = (o["assetId"] as? JsonPrimitive)?.contentOrNull,
            group = o.str("group", "sfx"), volume = o.num("volume", 1.0).toFloat(),
            loop = o.bool("loop"), autoplay = o.bool("autoplay"),
        )
    }

    /** Point-in-rect hit test. Rotation is ignored in Phase 3 (documented). */
    fun contains(px: Float, py: Float): Boolean {
        val t = transform()
        return px >= t.x && px <= t.x + t.w && py >= t.y && py <= t.y + t.h
    }

    fun deepCopy(newId: String, newSceneId: String): RtObject =
        RtObject(newId, newSceneId, kind, visible, layer, components.toMutableMap())
}

// ---------- scenes / events / variables / levels ----------
data class RtScene(val id: String, val name: String, val entry: Boolean, val background: JsonObject)

data class RtAction(val capability: String, val params: JsonObject) {
    companion object {
        fun parse(el: JsonElement?): RtAction? {
            val o = el as? JsonObject ?: return null
            val cap = (o["capability"] as? JsonPrimitive)?.contentOrNull ?: return null
            return RtAction(cap, o.obj("params") ?: JsonObject(emptyMap()))
        }
    }
}

data class RtEvent(
    val id: String,
    val trigger: JsonObject,
    val conditions: List<JsonObject>,
    val actions: List<RtAction>,
)

enum class VarType { NUMBER, STRING, BOOL }

sealed interface RtValue {
    data class Num(val v: Double) : RtValue
    data class Str(val v: String) : RtValue
    data class Bool(val v: Boolean) : RtValue

    fun asJson(): JsonElement = when (this) {
        is Num -> JsonPrimitive(v)
        is Str -> JsonPrimitive(v)
        is Bool -> JsonPrimitive(v)
    }
}

data class RtVarDef(val name: String, val type: VarType, val initial: RtValue, val persist: Boolean)

data class RtLevel(val id: String, val name: String)

// ---------- keyframe animations (project `animations[]`, played via CAP-0014) ----------
enum class AnimProp { X, Y, W, H, ROTATION, OPACITY }

data class RtKeyframe(val atMs: Long, val prop: AnimProp, val value: Float)

data class RtAnimation(val id: String, val durationMs: Long, val loop: Boolean, val frames: List<RtKeyframe>)
