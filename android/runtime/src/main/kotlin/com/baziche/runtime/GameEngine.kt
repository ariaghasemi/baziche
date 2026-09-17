package com.baziche.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

data class Gravity(var gx: Double = 0.0, var gy: Double = 0.0)

internal data class TimerState(val name: String, var remainingMs: Long, val durationMs: Long, val repeat: Boolean)

internal data class DelayedAction(var remainingMs: Long, val actions: List<RtAction>)

internal enum class TweenProp { X, Y, W, H, ROTATION, OPACITY }

internal data class TweenState(
    val objectId: String,
    val prop: TweenProp,
    val from: Float,
    val to: Float,
    var elapsedMs: Long,
    val durationMs: Long,
)

internal data class ActiveAnim(val objectId: String, val anim: RtAnimation, var elapsedMs: Long)

// ---------- render snapshot (engine -> canvas, no Android types) ----------
data class Drawable(
    val id: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotation: Float,
    val opacity: Float,
    val layer: Int,
    val text: RtTextData?,
    val sprite: RtSpriteData?,
    val button: RtButtonData?,
)

data class CameraState(val followId: String?, val x: Float, val y: Float)

data class RenderState(
    val sceneId: String,
    val bgColor: String,
    val transition: String,
    val drawables: List<Drawable>,
    val camera: CameraState,
    val uiVisible: Map<String, Boolean>,
    val vars: Map<String, JsonElement>,
    val particles: List<ParticleDrawable> = emptyList(),
) {
    companion object {
        val EMPTY = RenderState("", "#000000", "none", emptyList(), CameraState(null, 0f, 0f), emptyMap(), emptyMap())
    }
}

/**
 * Headless game interpreter: loads project JSON, runs events/capabilities on a
 * manual tick (the host drives timing), and emits render snapshots + warnings.
 * All platform effects go through [EngineSinks]. Deterministic given same inputs.
 */
class GameEngine(internal val sinks: EngineSinks) {
    private val runner = CapabilityRunner(this)

    private var scenes: List<RtScene> = emptyList()
    private val objects = mutableMapOf<String, RtObject>()
    private var events: List<RtEvent> = emptyList()
    private var levels: List<RtLevel> = emptyList()
    private var animations: List<RtAnimation> = emptyList()

    internal val assetIds = mutableSetOf<String>()
    lateinit var vars: VariableStore
        private set

    var projectName: String = ""
        private set
    var gameType: String = ""
        private set
    var orientation: String = "portrait"
        private set

    var currentSceneId: String? = null
        private set
    val sceneStack = mutableListOf<String>()
    var transition: String = "none"
        private set
    val uiVisible = mutableMapOf<String, Boolean>()
    val unlockedLevels = mutableSetOf<String>()
    var gravity = Gravity()
    var cameraFollowId: String? = null
        internal set

    private val timers = mutableListOf<TimerState>()
    private val pending = mutableListOf<DelayedAction>()
    private val tweens = mutableListOf<TweenState>()
    private val anims = mutableListOf<ActiveAnim>()
    private val lastDamageAt = mutableMapOf<String, Long>()
    private val warnings = mutableListOf<String>()
    private val particleSystem = ParticleSystem()
    private var particleConfig = ParticleConfig()
    private val particleRng = Random(7)
    private var trauma = 0f
    private var clockMs: Long = 0
    private var spawnCounter: Int = 0

    // ---------- lifecycle ----------
    fun load(root: JsonObject) {
        val lp = ProjectLoader.load(root)
        if (lp.scenes.isEmpty()) throw FormatException("project has no scenes")
        scenes = lp.scenes
        objects.clear()
        lp.objects.forEach { objects[it.id] = it }
        events = lp.events
        levels = lp.levels
        animations = lp.animations
        assetIds.clear()
        assetIds.addAll(lp.assetIds)
        vars = VariableStore(lp.varDefs)
        projectName = lp.name
        gameType = lp.gameType
        orientation = lp.orientation
        currentSceneId = null
        sceneStack.clear()
        transition = "none"
        uiVisible.clear()
        unlockedLevels.clear()
        gravity = Gravity()
        cameraFollowId = null
        timers.clear()
        pending.clear()
        tweens.clear()
        anims.clear()
        lastDamageAt.clear()
        warnings.clear()
        clockMs = 0
        spawnCounter = 0
        particleSystem.clear()
        trauma = 0f
        particleConfig = ParticleConfig.parse((root["systems"] as? JsonObject)?.get("ambientParticles") as? JsonObject)
    }

    fun start() {
        if (scenes.isEmpty()) throw FormatException("engine not loaded")
        val entry = scenes.firstOrNull { it.entry } ?: scenes.first()
        currentSceneId = entry.id
        transition = "none"
        fireTrigger(TYPE_START) { true }
        enterScene(entry.id)
    }

    fun tap(x: Float, y: Float) {
        val sceneId = currentSceneId ?: return
        val hit = objectsInScene(sceneId)
            .filter { it.visible }
            .sortedByDescending { it.layer }
            .firstOrNull { it.contains(x, y) }
        if (hit != null) {
            val btn = hit.button()
            if (btn != null && !btn.enabled) return // disabled button swallows the tap
            val btnEvent = btn?.eventId
            if (btnEvent != null) fireEventById(btnEvent)
            fireTrigger(TYPE_TAP) { trig -> trig.str("target", hit.id) == hit.id }
        } else {
            fireTrigger(TYPE_TAP) { trig -> (trig["target"] as? JsonPrimitive)?.contentOrNull == null }
        }
    }

    /**
     * Advances timers, delayed actions, tweens, animations and health regen by [dtMs].
     * Each timer fires at most once per tick (documented MVP semantics).
     */
    fun tick(dtMs: Long) {
        val dt = dtMs.coerceAtLeast(0)
        clockMs += dt
        if (dt > 0) {
            trauma = (trauma - dt / 500f).coerceAtLeast(0f)
            val w = if (orientation == "landscape") 800f else 480f
            val h = if (orientation == "landscape") 480f else 800f
            particleSystem.update(dt, particleConfig, w, h, particleRng)
        }
        // Timers (collect first: firing may start/cancel timers).
        val fired = timers.filter { t ->
            t.remainingMs -= dt
            t.remainingMs <= 0
        }
        for (t in fired) {
            fireTrigger(TYPE_TIMER) { trig -> trig.str("name") == t.name }
            if (t.repeat) t.remainingMs += t.durationMs else timers.remove(t)
        }
        // Delayed actions.
        val due = pending.filter { d ->
            d.remainingMs -= dt
            d.remainingMs <= 0
        }
        pending.removeAll(due.toSet())
        for (d in due) runner.run(d.actions)
        // Tweens.
        val done = mutableListOf<TweenState>()
        for (tw in tweens) {
            val o = objects[tw.objectId]
            if (o == null) {
                done.add(tw)
                continue
            }
            tw.elapsedMs += dt
            val k = (tw.elapsedMs.toFloat() / tw.durationMs.toFloat()).coerceIn(0f, 1f)
            val tr = o.transform()
            val v = tw.from + (tw.to - tw.from) * k
            when (tw.prop) {
                TweenProp.X -> tr.x = v
                TweenProp.Y -> tr.y = v
                TweenProp.W -> tr.w = v
                TweenProp.H -> tr.h = v
                TweenProp.ROTATION -> tr.rotation = v
                TweenProp.OPACITY -> tr.opacity = v
            }
            o.setTransform(tr)
            if (k >= 1f) done.add(tw)
        }
        tweens.removeAll(done.toSet())
        // Keyframe animations.
        val finished = mutableListOf<ActiveAnim>()
        for (an in anims) {
            val o = objects[an.objectId]
            if (o == null) {
                finished.add(an)
                continue
            }
            an.elapsedMs += dt
            val dur = an.anim.durationMs
            if (dur <= 0) {
                applyAnimFrame(o, an.anim, Long.MAX_VALUE)
                finished.add(an)
                continue
            }
            var t = an.elapsedMs
            if (t >= dur) {
                if (an.anim.loop) {
                    t %= dur
                    an.elapsedMs = t
                } else {
                    applyAnimFrame(o, an.anim, dur)
                    finished.add(an)
                    continue
                }
            }
            applyAnimFrame(o, an.anim, t)
        }
        anims.removeAll(finished.toSet())
        // Health regen (per second).
        if (dt > 0) {
            for (o in objects.values) {
                val h = o.health() ?: continue
                if (h.regen > 0 && h.current < h.max && h.current > 0) {
                    h.current = (h.current + h.regen * dt / 1000.0).coerceAtMost(h.max)
                    o.setHealth(h)
                }
            }
        }
    }

    private fun applyAnimFrame(o: RtObject, anim: RtAnimation, t: Long) {
        val tr = o.transform()
        for (prop in AnimProp.entries) {
            val keys = anim.frames.filter { it.prop == prop }
            if (keys.isEmpty()) continue
            tr.setProp(prop, sampleKeys(keys, t))
        }
        o.setTransform(tr)
    }

    private fun sampleKeys(keys: List<RtKeyframe>, t: Long): Float {
        if (t <= keys.first().atMs) return keys.first().value
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (t <= b.atMs) {
                val span = (b.atMs - a.atMs).coerceAtLeast(1)
                val k = ((t - a.atMs).toFloat() / span).coerceIn(0f, 1f)
                return a.value + (b.value - a.value) * k
            }
        }
        return keys.last().value
    }

    private fun RtTransform.setProp(prop: AnimProp, v: Float) {
        when (prop) {
            AnimProp.X -> x = v
            AnimProp.Y -> y = v
            AnimProp.W -> w = v
            AnimProp.H -> h = v
            AnimProp.ROTATION -> rotation = v
            AnimProp.OPACITY -> opacity = v
        }
    }

    fun snapshot(): RenderState {
        val sceneId = currentSceneId ?: return RenderState.EMPTY
        val scene = scenes.firstOrNull { it.id == sceneId } ?: return RenderState.EMPTY
        val drawables = objectsInScene(sceneId)
            .filter { it.visible }
            .sortedBy { it.layer }
            .map { o ->
                val t = o.transform()
                Drawable(o.id, o.kind, t.x, t.y, t.w, t.h, t.rotation, t.opacity, o.layer, o.text(), o.sprite(), o.button())
            }
        val follow = cameraFollowId?.let { objects[it] }?.transform()
        val sh = trauma * trauma * 14f
        val shx = if (sh > 0.01f) (particleRng.nextFloat() * 2f - 1f) * sh else 0f
        val shy = if (sh > 0.01f) (particleRng.nextFloat() * 2f - 1f) * sh else 0f
        return RenderState(
            sceneId = sceneId,
            bgColor = scene.background.str("color", "#121212"),
            transition = transition,
            drawables = drawables,
            camera = CameraState(cameraFollowId, (follow?.x ?: 0f) + shx, (follow?.y ?: 0f) + shy),
            uiVisible = uiVisible.toMap(),
            vars = vars.snapshotAll(),
            particles = particleSystem.drawables(),
        )
    }

    // ---------- queries ----------
    fun objectById(id: String): RtObject? = objects[id]

    fun objectsInScene(sceneId: String): List<RtObject> = objects.values.filter { it.sceneId == sceneId }

    fun drainWarnings(): List<String> {
        val out = warnings.toList()
        warnings.clear()
        return out
    }

    internal fun warn(msg: String) {
        warnings.add(msg)
    }

    internal fun timerCount(): Int = timers.size
    internal fun tweenCount(): Int = tweens.size
    internal fun pendingCount(): Int = pending.size
    internal fun animCount(): Int = anims.size
    internal fun particleCount(): Int = particleSystem.particles.size
    internal fun traumaLevel(): Float = trauma

    /** Screen-shake impulse 0..1 (decays automatically). CAP-0022 damage also fires it. */
    fun addTrauma(x: Float) {
        trauma = (trauma + x).coerceIn(0f, 1f)
    }

    // ---------- events ----------
    fun fireEventById(id: String) {
        val ev = events.firstOrNull { it.id == id }
        if (ev == null) {
            warn("unknown event '$id'")
            return
        }
        fireEvent(ev)
    }

    private fun fireTrigger(type: String, match: (JsonObject) -> Boolean) {
        for (ev in events) {
            if (ev.trigger.str("type") != type) continue
            if (!match(ev.trigger)) continue
            fireEvent(ev)
        }
    }

    private fun fireEvent(ev: RtEvent) {
        if (!ev.conditions.all { Conditions.eval(it, vars) }) return
        runner.run(ev.actions)
    }

    private fun enterScene(sceneId: String) {
        fireTrigger(TYPE_SCENE_ENTER) { trig -> trig.str("sceneId", sceneId) == sceneId }
        autoplaySceneAudio(sceneId)
    }

    private fun autoplaySceneAudio(sceneId: String) {
        for (o in objectsInScene(sceneId)) {
            val a = o.audio() ?: continue
            if (!a.autoplay) continue
            val id = a.assetId
            if (id == null || !assetIds.contains(id)) {
                warn("autoplay: unknown asset '${id ?: "?"}' on '${o.id}'")
                continue
            }
            sinks.audio.play(id, a.volume.coerceIn(0f, 1f), a.loop)
        }
    }

    // ---------- capability backends ----------
    internal fun changeScene(id: String, trans: String) {
        if (scenes.none { it.id == id }) {
            warn("CAP-0015: unknown scene '$id'")
            return
        }
        currentSceneId?.let { sceneStack.add(it) }
        currentSceneId = id
        transition = trans.ifBlank { "fade" }
        enterScene(id)
    }

    internal fun navigateBack() {
        if (sceneStack.isEmpty()) return
        val id = sceneStack.removeAt(sceneStack.lastIndex)
        if (scenes.none { it.id == id }) return
        currentSceneId = id
        transition = "back"
        enterScene(id)
    }

    internal fun destroyObject(id: String): Boolean {
        if (objects.remove(id) == null) return false
        tweens.removeAll { it.objectId == id }
        anims.removeAll { it.objectId == id }
        lastDamageAt.remove(id)
        return true
    }

    internal fun spawnObject(prefabId: String, x: Float, y: Float): String? {
        val sceneId = currentSceneId
        val prefab = objects[prefabId]
        if (prefab == null) {
            warn("CAP-0005: unknown prefab '$prefabId'")
            return null
        }
        if (sceneId == null) {
            warn("CAP-0005: no active scene")
            return null
        }
        var newId = "spawn_${++spawnCounter}"
        while (objects.containsKey(newId)) newId = "spawn_${++spawnCounter}"
        val clone = prefab.deepCopy(newId, sceneId)
        val tr = clone.transform()
        clone.setTransform(tr.copy(x = x, y = y))
        objects[newId] = clone
        return newId
    }

    internal fun startTimer(name: String, durationMs: Long, repeat: Boolean) {
        timers.removeAll { it.name == name }
        timers.add(TimerState(name, durationMs, durationMs, repeat))
    }

    internal fun scheduleDelay(durationMs: Long, actions: List<RtAction>) {
        pending.add(DelayedAction(durationMs, actions))
    }

    internal fun addTween(objectId: String, prop: TweenProp, from: Float, to: Float, durationMs: Long) {
        tweens.removeAll { it.objectId == objectId && it.prop == prop }
        tweens.add(TweenState(objectId, prop, from, to, 0, durationMs))
    }

    internal fun playAnimation(targetId: String, animId: String) {
        val o = objects[targetId]
        if (o == null) {
            warn("CAP-0014: unknown target '$targetId'")
            return
        }
        val anim = animations.firstOrNull { it.id == animId }
        if (anim == null) {
            warn("CAP-0014: unknown animation '$animId'")
            return
        }
        anims.removeAll { it.objectId == targetId }
        anims.add(ActiveAnim(targetId, anim, 0))
    }

    internal fun damage(target: String, amount: Double) {
        val o = objects[target]
        if (o == null) {
            warn("CAP-0022: unknown target '$target'")
            return
        }
        val h = o.health()
        if (h == null) {
            warn("CAP-0022: '$target' has no Health")
            return
        }
        val lastAt = lastDamageAt[target]
        if (lastAt != null && h.invincibleMs > 0 && clockMs - lastAt < h.invincibleMs) return
        lastDamageAt[target] = clockMs
        h.current = (h.current - amount).coerceAtLeast(0.0)
        o.setHealth(h)
        addTrauma(0.35f)
        val t = o.transform()
        particleSystem.burst(t.x + t.w / 2f, t.y + t.h / 2f, 10, "#FF5252", 160f, 600, 5f, particleRng)
    }

    internal fun heal(target: String, amount: Double) {
        val o = objects[target]
        if (o == null) {
            warn("CAP-0023: unknown target '$target'")
            return
        }
        val h = o.health()
        if (h == null) {
            warn("CAP-0023: '$target' has no Health")
            return
        }
        h.current = (h.current + amount).coerceAtMost(h.max)
        o.setHealth(h)
    }

    internal fun unlockLevel(id: String) {
        if (levels.none { it.id == id }) warn("CAP-0024: unknown level '$id' (recorded anyway)")
        unlockedLevels.add(id)
    }

    internal fun saveGame(slot: String) {
        val data = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("scene", JsonPrimitive(currentSceneId ?: ""))
            put("vars", JsonObject(vars.snapshotAll()))
            put("unlocked", JsonArray(unlockedLevels.map { JsonPrimitive(it) }))
            put("clockMs", JsonPrimitive(clockMs))
        }
        sinks.save.save(slot, data.toString())
    }

    internal fun loadGame(slot: String) {
        val raw = sinks.save.load(slot)
        if (raw == null) {
            warn("CAP-0019: empty slot '$slot'")
            return
        }
        val o = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }
        if (o == null) {
            warn("CAP-0019: corrupt slot '$slot'")
            return
        }
        val scene = o.str("scene")
        if (scene.isNotBlank() && scenes.any { it.id == scene }) {
            sceneStack.clear()
            currentSceneId = scene
            transition = "fade"
        }
        (o["vars"] as? JsonObject)?.let { vars.restore(it.toMap()) }
        unlockedLevels.clear()
        (o["unlocked"] as? JsonArray)?.forEach {
            (it as? JsonPrimitive)?.contentOrNull?.let { id -> unlockedLevels.add(id) }
        }
        currentSceneId?.let { enterScene(it) }
    }

    companion object {
        const val TYPE_START = "start"
        const val TYPE_TAP = "tap"
        const val TYPE_TIMER = "timer"
        const val TYPE_SCENE_ENTER = "sceneEnter"
    }
}
