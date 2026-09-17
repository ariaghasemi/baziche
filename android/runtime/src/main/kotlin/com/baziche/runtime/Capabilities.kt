package com.baziche.runtime

import com.baziche.runtime.RtValue.Bool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Executes CAP-xxxx actions against the engine. Every failure mode degrades to
 * a drained warning — a bad action never crashes the game and never lies.
 * Support matrix: runtime/README.md.
 */
internal class CapabilityRunner(private val e: GameEngine) {
    fun run(actions: List<RtAction>, depth: Int = 0) {
        if (depth > MAX_DEPTH) {
            e.warn("action nesting too deep (>$MAX_DEPTH), stopped")
            return
        }
        for (a in actions) runOne(a, depth)
    }

    private fun runOne(a: RtAction, depth: Int) {
        val p = a.params
        when (a.capability) {
            "CAP-0001" -> { // Move (relative; tweened when durationMs > 0)
                val t = needTarget(a.capability, p) ?: return
                val dx = num(p, "dx").toFloat()
                val dy = num(p, "dy").toFloat()
                val dur = num(p, "durationMs").toLong()
                val tr = t.transform()
                if (dur > 0) {
                    e.addTween(t.id, TweenProp.X, tr.x, tr.x + dx, dur)
                    e.addTween(t.id, TweenProp.Y, tr.y, tr.y + dy, dur)
                } else {
                    t.setTransform(tr.copy(x = tr.x + dx, y = tr.y + dy))
                }
            }
            "CAP-0002" -> { // Rotate (absolute angle)
                val t = needTarget(a.capability, p) ?: return
                val angle = num(p, "angle").toFloat()
                val dur = num(p, "durationMs").toLong()
                val tr = t.transform()
                if (dur > 0) e.addTween(t.id, TweenProp.ROTATION, tr.rotation, angle, dur)
                else t.setTransform(tr.copy(rotation = angle))
            }
            "CAP-0003" -> { // Scale (multiplies current size)
                val t = needTarget(a.capability, p) ?: return
                val sx = num(p, "sx", 1.0).toFloat()
                val sy = num(p, "sy", 1.0).toFloat()
                val tr = t.transform()
                t.setTransform(tr.copy(w = (tr.w * sx).coerceAtLeast(1f), h = (tr.h * sy).coerceAtLeast(1f)))
            }
            "CAP-0004" -> { // Destroy
                val id = str(p, "target")
                if (id.isBlank() || !e.destroyObject(id)) e.warn("CAP-0004: unknown target '$id'")
            }
            "CAP-0005" -> { // Spawn (clone any object as prefab into the current scene)
                val prefab = str(p, "prefab")
                if (prefab.isBlank()) {
                    e.warn("CAP-0005: prefab required")
                    return
                }
                e.spawnObject(prefab, num(p, "x").toFloat(), num(p, "y").toFloat())
            }
            "CAP-0006" -> { // Set Position
                val t = needTarget(a.capability, p) ?: return
                val tr = t.transform()
                t.setTransform(tr.copy(x = num(p, "x").toFloat(), y = num(p, "y").toFloat()))
            }
            "CAP-0007" -> { // Set Variable
                val name = str(p, "name")
                val raw = p["value"]
                if (name.isBlank() || raw == null) {
                    e.warn("CAP-0007: name + value required")
                    return
                }
                val v = Conditions.resolve(raw, e.vars)
                if (v == null || !e.vars.setValue(name, v)) e.warn("CAP-0007: unknown variable '$name'")
            }
            "CAP-0008" -> { // Get Variable -> $last
                val name = str(p, "name")
                val v = if (name.isBlank()) null else e.vars.get(name)
                if (v == null) e.warn("CAP-0008: unknown variable '$name'") else e.vars.last = v
            }
            "CAP-0009" -> { // If
                val cond = p.obj("condition")
                if (cond == null) {
                    e.warn("CAP-0009: condition required")
                    return
                }
                run(if (Conditions.eval(cond, e.vars)) branch(p["then"]) else branch(p["else"]), depth + 1)
            }
            "CAP-0010" -> { // Compare -> $last (bool)
                e.vars.last = Bool(Conditions.eval(p, e.vars))
            }
            "CAP-0011" -> { // Start Timer
                val name = str(p, "name")
                val dur = num(p, "durationMs").toLong()
                if (name.isBlank() || dur <= 0) {
                    e.warn("CAP-0011: name + positive durationMs required")
                    return
                }
                e.startTimer(name, dur, boolP(p, "repeat"))
            }
            "CAP-0012" -> { // Delay
                val dur = num(p, "durationMs").toLong()
                val then = branch(p["then"])
                if (dur <= 0) run(then, depth + 1) else e.scheduleDelay(dur, then)
            }
            "CAP-0013" -> { // Play Sound
                val assetId = str(p, "assetId")
                if (assetId.isBlank()) {
                    e.warn("CAP-0013: assetId required")
                    return
                }
                if (!e.assetIds.contains(assetId)) {
                    e.warn("CAP-0013: unknown asset '$assetId'")
                    return
                }
                e.sinks.audio.play(assetId, num(p, "volume", 1.0).toFloat().coerceIn(0f, 1f), false)
            }
            "CAP-0014" -> { // Play Animation (keyframe player)
                val target = str(p, "target")
                val animId = str(p, "animationId")
                if (target.isBlank() || animId.isBlank()) {
                    e.warn("CAP-0014: target + animationId required")
                    return
                }
                e.playAnimation(target, animId)
            }
            "CAP-0015" -> { // Change Scene
                val id = str(p, "sceneId")
                if (id.isBlank()) {
                    e.warn("CAP-0015: sceneId required")
                    return
                }
                e.changeScene(id, str(p, "transition", "fade"))
            }
            "CAP-0016" -> { // Show UI
                val id = str(p, "screenId")
                if (id.isBlank()) e.warn("CAP-0016: screenId required") else e.uiVisible[id] = true
            }
            "CAP-0017" -> { // Hide UI
                val id = str(p, "screenId")
                if (id.isBlank()) e.warn("CAP-0017: screenId required") else e.uiVisible[id] = false
            }
            "CAP-0018" -> e.saveGame(str(p, "slot", "auto").ifBlank { "auto" })
            "CAP-0019" -> e.loadGame(str(p, "slot", "auto").ifBlank { "auto" })
            "CAP-0020", "CAP-0021" -> { // Add Score / Add Currency
                val name = str(p, "variable")
                val cur = if (name.isBlank()) null else e.vars.get(name)
                if (cur == null) {
                    e.warn("${a.capability}: unknown variable '$name'")
                    return
                }
                e.vars.setValue(name, com.baziche.runtime.RtValue.Num(VariableStore.asNumber(cur) + num(p, "amount")))
            }
            "CAP-0022" -> { // Damage
                val target = str(p, "target")
                if (target.isBlank()) {
                    e.warn("CAP-0022: target required")
                    return
                }
                e.damage(target, num(p, "amount"))
            }
            "CAP-0023" -> { // Heal
                val target = str(p, "target")
                if (target.isBlank()) {
                    e.warn("CAP-0023: target required")
                    return
                }
                e.heal(target, num(p, "amount"))
            }
            "CAP-0024" -> { // Unlock Level
                val id = str(p, "levelId")
                if (id.isBlank()) e.warn("CAP-0024: levelId required") else e.unlockLevel(id)
            }
            "CAP-0025" -> e.warn("CAP-0025 Checkpoint: respawn arrives in Phase 4 (not executed)")
            "CAP-0026" -> e.sinks.haptic.vibrate(num(p, "durationMs", 50.0).toInt().coerceIn(0, 5000))
            "CAP-0027" -> { // Open Link
                val url = str(p, "url")
                if (!isSafeUrl(url)) {
                    e.warn("CAP-0027: bad url")
                    return
                }
                e.sinks.system.openLink(url)
            }
            "CAP-0028" -> { // Rewarded Ad (host shows it, reports back via onRewardedAdResult)
                e.monetization.onRewardedAdRequested(str(p, "placement", "default").ifBlank { "default" })
            }
            "CAP-0029" -> { // Purchase (host verifies server-side, reports back via onPurchaseResult)
                val sku = str(p, "sku")
                if (sku.isBlank()) e.warn("CAP-0029: sku required")
                else e.monetization.onPurchaseRequested(sku, str(p, "developerPayload", ""))
            }
            "CAP-0030" -> e.navigateBack()
            "CAP-0031" -> e.gravity = Gravity(gx = num(p, "gx"), gy = num(p, "gy"))
            "CAP-0032" -> { // Camera Follow
                val target = str(p, "target")
                if (target.isBlank()) {
                    e.cameraFollowId = null
                    return
                }
                if (e.objectById(target) == null) {
                    e.warn("CAP-0032: unknown target '$target'")
                    return
                }
                e.cameraFollowId = target
            }
            else -> e.warn("unknown capability ${a.capability} (not executed)")
        }
    }

    private fun needTarget(cap: String, p: JsonObject): RtObject? {
        val id = str(p, "target")
        val t = if (id.isBlank()) null else e.objectById(id)
        if (t == null) e.warn("$cap: unknown target '$id'")
        return t
    }

    /** String param with `$variable` / `$last` resolution. */
    private fun str(p: JsonObject, key: String, default: String = ""): String {
        val el = p[key] as? JsonPrimitive ?: return default
        if (el.isString && el.content.startsWith("$") && el.content.length > 1) {
            val name = el.content.drop(1)
            val v = if (name == "last") e.vars.last else e.vars.get(name)
            return v?.let { VariableStore.asString(it) } ?: default
        }
        return el.contentOrNull ?: default
    }

    private fun num(p: JsonObject, key: String, default: Double = 0.0): Double {
        val v = Conditions.resolve(p[key], e.vars) ?: return default
        return VariableStore.asNumber(v)
    }

    private fun boolP(p: JsonObject, key: String, default: Boolean = false): Boolean {
        val v = Conditions.resolve(p[key], e.vars) ?: return default
        return VariableStore.asBool(v)
    }

    private fun branch(el: JsonElement?): List<RtAction> =
        (el as? JsonArray)?.mapNotNull { RtAction.parse(it) } ?: emptyList()

    private fun isSafeUrl(url: String): Boolean {
        if (url.isBlank() || url.length > 2048 || url.any { it.isWhitespace() }) return false
        return Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.+").matches(url)
    }

    companion object {
        const val MAX_DEPTH = 16
    }
}
