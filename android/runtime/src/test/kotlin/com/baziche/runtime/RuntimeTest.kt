package com.baziche.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// serialization 1.11 keeps only put(String, JsonElement): restore primitive overloads locally.
private fun JsonObjectBuilder.put(key: String, value: String) {
    put(key, JsonPrimitive(value))
}
private fun JsonObjectBuilder.put(key: String, value: Number) {
    put(key, JsonPrimitive(value))
}
private fun JsonObjectBuilder.put(key: String, value: Boolean) {
    put(key, JsonPrimitive(value))
}

// ---------- builders ----------
private fun v(n: Number): JsonElement = JsonPrimitive(n)
private fun v(s: String): JsonElement = JsonPrimitive(s)
private fun v(b: Boolean): JsonElement = JsonPrimitive(b)

private fun act(cap: String, vararg params: Pair<String, JsonElement>): JsonObject =
    buildJsonObject {
        put("capability", cap)
        put("params", JsonObject(params.toMap()))
    }

private fun ev(
    id: String,
    type: String,
    actions: List<JsonObject>,
    target: String? = null,
    name: String? = null,
    sceneId: String? = null,
    conditions: List<JsonObject> = emptyList(),
): JsonObject = buildJsonObject {
    put("id", id)
    put("trigger", buildJsonObject {
        put("type", type)
        if (target != null) put("target", target)
        if (name != null) put("name", name)
        if (sceneId != null) put("sceneId", sceneId)
    })
    put("actions", JsonArray(actions))
    if (conditions.isNotEmpty()) put("conditions", JsonArray(conditions))
}

private fun cond(a: JsonElement, op: String, b: JsonElement): JsonObject =
    buildJsonObject {
        put("a", a)
        put("op", op)
        put("b", b)
    }

private fun scene(id: String, entry: Boolean, color: String = "#121212"): JsonObject = buildJsonObject {
    put("id", id)
    put("name", id)
    put("entry", entry)
    put("background", buildJsonObject { put("color", color) })
    put("objectIds", JsonArray(emptyList()))
    put("transitions", JsonArray(emptyList()))
}

private fun obj(
    id: String,
    scene: String,
    kind: String = "rect",
    x: Number = 0,
    y: Number = 0,
    w: Number = 100,
    h: Number = 100,
    layer: Int = 0,
    visible: Boolean = true,
    comps: Map<String, JsonObject> = emptyMap(),
): JsonObject = buildJsonObject {
    put("id", id)
    put("sceneId", scene)
    put("kind", kind)
    put("visible", visible)
    put("layer", layer)
    val all = comps.toMutableMap()
    if ("Transform" !in all) {
        all["Transform"] = buildJsonObject {
            put("x", JsonPrimitive(x))
            put("y", JsonPrimitive(y))
            put("w", JsonPrimitive(w))
            put("h", JsonPrimitive(h))
        }
    }
    put("components", JsonObject(all))
}

private fun numVar(name: String, init: Number = 0): JsonObject = buildJsonObject {
    put("name", name)
    put("type", "number")
    put("initial", JsonPrimitive(init))
}

private fun project(
    scenes: List<JsonObject>,
    objects: List<JsonObject> = emptyList(),
    events: List<JsonObject> = emptyList(),
    vars: List<JsonObject> = emptyList(),
    assets: List<String> = emptyList(),
    levels: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("formatVersion", 1)
    put("meta", buildJsonObject {
        put("name", "T")
        put("gameType", "quiz")
        put("orientation", "portrait")
    })
    put("scenes", JsonArray(scenes))
    put("objects", JsonArray(objects))
    put("events", JsonArray(events))
    put("variables", JsonArray(vars))
    put("assets", JsonArray(assets.map {
        buildJsonObject {
            put("id", it)
            put("kind", "audio")
            put("hash", "0".repeat(64))
        }
    }))
    put("levels", JsonArray(levels.map {
        buildJsonObject {
            put("id", it)
            put("name", it)
        }
    }))
}

// ---------- recording sinks ----------
private class RecAudio : AudioSink {
    data class Call(val assetId: String, val volume: Float, val loop: Boolean)
    val calls = mutableListOf<Call>()
    override fun play(assetId: String, volume: Float, loop: Boolean) {
        calls.add(Call(assetId, volume, loop))
    }
}

private class RecHaptic : HapticSink {
    val calls = mutableListOf<Int>()
    override fun vibrate(durationMs: Int) {
        calls.add(durationMs)
    }
}

private class RecSystem : SystemSink {
    val calls = mutableListOf<String>()
    override fun openLink(url: String) {
        calls.add(url)
    }
}

private class MemSave : SaveSink {
    val mem = mutableMapOf<String, String>()
    override fun save(slot: String, data: String) {
        mem[slot] = data
    }
    override fun load(slot: String): String? = mem[slot]
}

private class Fx {
    val audio = RecAudio()
    val haptic = RecHaptic()
    val system = RecSystem()
    val save = MemSave()
    val engine = GameEngine(EngineSinks(audio, haptic, system, save))
}

class RuntimeTest {
    // ---------- loader ----------
    @Test
    fun load_rejects_bad_version() {
        for (bad in listOf(0, 2, -1)) {
            try {
                GameEngine(NoopSinks.ALL).load(buildJsonObject { put("formatVersion", bad) })
                fail("version $bad must be rejected")
            } catch (e: FormatException) {
                assertTrue(e.message!!.contains("UNSUPPORTED_FORMAT"))
            }
        }
        try {
            GameEngine(NoopSinks.ALL).load(buildJsonObject { put("formatVersion", 1) })
            fail("no scenes must be rejected")
        } catch (e: FormatException) {
            assertTrue(e.message!!.contains("no scenes"))
        }
    }

    @Test
    fun loader_skips_broken_entries() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(
                    buildJsonObject { put("kind", "rect") }, // no id/sceneId
                    obj("o1", "s1"),
                ),
                events = listOf(
                    buildJsonObject { put("id", "broken") }, // no trigger
                    ev("e1", "tap", listOf(act("CAP-0030"))),
                ),
                vars = listOf(
                    buildJsonObject {
                        put("name", "bad")
                        put("type", "color")
                    },
                    numVar("score"),
                ),
            ),
        )
        fx.engine.start()
        assertNotNull(fx.engine.objectById("o1"))
        assertEquals(1, fx.engine.objectsInScene("s1").size)
        assertEquals(RtValue.Num(0.0), fx.engine.vars.get("score"))
        fx.engine.tap(50f, 50f) // broken entries never break the run
        assertTrue(fx.engine.drainWarnings().isEmpty())
    }

    @Test
    fun start_picks_entry_scene_and_snapshot() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", false, "#111111"), scene("s2", true, "#222222")),
                objects = listOf(
                    obj("low", "s2", layer = 0),
                    obj("high", "s2", layer = 5),
                    obj("hidden", "s2", visible = false),
                    obj("other", "s1"),
                ),
            ),
        )
        // tap/snapshot before start are safe no-ops
        fx.engine.tap(10f, 10f)
        assertEquals(RenderState.EMPTY, fx.engine.snapshot())
        fx.engine.start()
        assertEquals("s2", fx.engine.currentSceneId)
        val snap = fx.engine.snapshot()
        assertEquals("#222222", snap.bgColor)
        assertEquals(listOf("low", "high"), snap.drawables.map { it.id })
        assertEquals("portrait", fx.engine.orientation)
    }

    // ---------- tap / buttons / conditions ----------
    @Test
    fun tap_button_adds_score_and_fires_eventId() {
        val fx = Fx()
        val btn = buildJsonObject {
            put("label", "Hit")
            put("eventId", "bonus")
            put("enabled", true)
        }
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("b1", "s1", "button", x = 10, y = 10, w = 100, h = 50, comps = mapOf("Button" to btn))),
                events = listOf(
                    ev("tapEv", "tap", listOf(act("CAP-0020", "variable" to v("score"), "amount" to v(1))), target = "b1"),
                    ev("bonus", "tap", listOf(act("CAP-0020", "variable" to v("score"), "amount" to v(10))), target = "nope"),
                ),
                vars = listOf(numVar("score")),
            ),
        )
        fx.engine.start()
        fx.engine.tap(50f, 30f)
        // +1 from tap event, +10 from Button.eventId
        assertEquals(RtValue.Num(11.0), fx.engine.vars.get("score"))
        assertTrue(fx.engine.drainWarnings().isEmpty())
    }

    @Test
    fun tap_empty_fires_global_only_and_disabled_swallows() {
        val fx = Fx()
        val disabled = buildJsonObject {
            put("label", "X")
            put("enabled", false)
        }
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("d1", "s1", "button", x = 0, y = 0, w = 50, h = 50, comps = mapOf("Button" to disabled))),
                events = listOf(
                    ev("g", "tap", listOf(act("CAP-0020", "variable" to v("score"), "amount" to v(1)))),
                ),
                vars = listOf(numVar("score")),
            ),
        )
        fx.engine.start()
        fx.engine.tap(10f, 10f) // on disabled button -> swallowed
        assertEquals(RtValue.Num(0.0), fx.engine.vars.get("score"))
        fx.engine.tap(400f, 400f) // empty space -> global tap
        assertEquals(RtValue.Num(1.0), fx.engine.vars.get("score"))
    }

    @Test
    fun conditions_gate_events() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("b1", "s1", x = 0, y = 0, w = 100, h = 100)),
                events = listOf(
                    ev(
                        "gated", "tap",
                        listOf(act("CAP-0020", "variable" to v("score"), "amount" to v(5))),
                        target = "b1",
                        conditions = listOf(cond(v("\$score"), ">=", v(10))),
                    ),
                ),
                vars = listOf(numVar("score", 3)),
            ),
        )
        fx.engine.start()
        fx.engine.tap(50f, 50f)
        assertEquals(RtValue.Num(3.0), fx.engine.vars.get("score"))
        fx.engine.vars.set("score", JsonPrimitive(10))
        fx.engine.tap(50f, 50f)
        assertEquals(RtValue.Num(15.0), fx.engine.vars.get("score"))
    }

    @Test
    fun if_then_else_and_compare_last() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                events = listOf(
                    ev(
                        "onStart", "start",
                        listOf(
                            act("CAP-0010", "a" to v(2), "op" to v(">"), "b" to v(1)),
                            act(
                                "CAP-0009",
                                "condition" to cond(v("\$last"), "==", v(true)),
                                "then" to JsonArray(listOf(act("CAP-0007", "name" to v("mode"), "value" to v("big")))),
                                "else" to JsonArray(listOf(act("CAP-0007", "name" to v("mode"), "value" to v("small")))),
                            ),
                            act(
                                "CAP-0009",
                                "condition" to cond(v(1), "==", v(2)),
                                "then" to JsonArray(listOf(act("CAP-0007", "name" to v("flag"), "value" to v(true)))),
                                "else" to JsonArray(listOf(act("CAP-0007", "name" to v("flag"), "value" to v(false)))),
                            ),
                        ),
                    ),
                ),
                vars = listOf(
                    buildJsonObject {
                        put("name", "mode")
                        put("type", "string")
                        put("initial", v("?"))
                    },
                    buildJsonObject {
                        put("name", "flag")
                        put("type", "bool")
                        put("initial", v(true))
                    },
                ),
            ),
        )
        fx.engine.start()
        assertEquals(RtValue.Bool(true), fx.engine.vars.last)
        assertEquals(RtValue.Str("big"), fx.engine.vars.get("mode"))
        assertEquals(RtValue.Bool(false), fx.engine.vars.get("flag"))
    }

    @Test
    fun set_get_variable_with_refs() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                events = listOf(
                    ev(
                        "onStart", "start",
                        listOf(
                            act("CAP-0008", "name" to v("a")),
                            act("CAP-0007", "name" to v("b"), "value" to v("\$last")),
                            act("CAP-0007", "name" to v("nope"), "value" to v(1)),
                        ),
                    ),
                ),
                vars = listOf(numVar("a", 7), numVar("b")),
            ),
        )
        fx.engine.start()
        assertEquals(RtValue.Num(7.0), fx.engine.vars.get("b"))
        val w = fx.engine.drainWarnings()
        assertEquals(1, w.size)
        assertTrue(w[0].contains("CAP-0007"))
    }

    // ---------- scenes ----------
    @Test
    fun change_scene_and_navigate_back() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true), scene("s2", false)),
                events = listOf(
                    ev("go", "start", listOf(act("CAP-0015", "sceneId" to v("s2"), "transition" to v("slide")))),
                ),
            ),
        )
        fx.engine.start()
        assertEquals("s2", fx.engine.currentSceneId)
        assertEquals("slide", fx.engine.snapshot().transition)
        fx.engine.fireEventById("nope")
        assertTrue(fx.engine.drainWarnings().any { it.contains("unknown event") })
        // back via capability
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true), scene("s2", false)),
                events = listOf(ev("go", "start", listOf(act("CAP-0015", "sceneId" to v("s2"))))),
            ),
        )
        fx.engine.start()
        fx.engine.fireEventById("go") // already there; stack grows
        assertEquals(listOf("s1", "s2"), fx.engine.sceneStack)
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true), scene("s2", false)),
                events = listOf(ev("back", "start", listOf(act("CAP-0030")))),
            ),
        )
        fx.engine.start() // empty stack -> silent no-op
        assertEquals("s1", fx.engine.currentSceneId)
        assertTrue(fx.engine.drainWarnings().isEmpty())
    }

    @Test
    fun change_scene_unknown_warns_and_stays() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                events = listOf(ev("go", "start", listOf(act("CAP-0015", "sceneId" to v("void"))))),
            ),
        )
        fx.engine.start()
        assertEquals("s1", fx.engine.currentSceneId)
        assertTrue(fx.engine.drainWarnings().any { it.contains("CAP-0015") })
    }

    // ---------- timers / delays / tweens ----------
    @Test
    fun timer_once_and_repeat() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0011", "name" to v("once"), "durationMs" to v(100)),
                            act("CAP-0011", "name" to v("rep"), "durationMs" to v(100), "repeat" to v(true)),
                        ),
                    ),
                    ev("onOnce", "timer", listOf(act("CAP-0020", "variable" to v("n"), "amount" to v(1))), name = "once"),
                    ev("onRep", "timer", listOf(act("CAP-0020", "variable" to v("n"), "amount" to v(10))), name = "rep"),
                ),
                vars = listOf(numVar("n")),
            ),
        )
        fx.engine.start()
        assertEquals(2, fx.engine.timerCount())
        fx.engine.tick(50)
        assertEquals(RtValue.Num(0.0), fx.engine.vars.get("n"))
        fx.engine.tick(50) // both fire
        assertEquals(RtValue.Num(11.0), fx.engine.vars.get("n"))
        assertEquals(1, fx.engine.timerCount()) // one-shot gone
        fx.engine.tick(100) // repeat fires again
        assertEquals(RtValue.Num(21.0), fx.engine.vars.get("n"))
    }

    @Test
    fun delay_runs_then_actions() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act(
                                "CAP-0012", "durationMs" to v(200),
                                "then" to JsonArray(listOf(act("CAP-0007", "name" to v("msg"), "value" to v("late")))),
                            ),
                            act("CAP-0007", "name" to v("msg"), "value" to v("now")),
                        ),
                    ),
                ),
                vars = listOf(
                    buildJsonObject {
                        put("name", "msg")
                        put("type", "string")
                        put("initial", v(""))
                    },
                ),
            ),
        )
        fx.engine.start()
        assertEquals(RtValue.Str("now"), fx.engine.vars.get("msg"))
        assertEquals(1, fx.engine.pendingCount())
        fx.engine.tick(200)
        assertEquals(RtValue.Str("late"), fx.engine.vars.get("msg"))
        assertEquals(0, fx.engine.pendingCount())
    }

    @Test
    fun tween_move_completes_and_destroy_cancels() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("m1", "s1", x = 0, y = 0), obj("m2", "s1", x = 0, y = 0)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0001", "target" to v("m1"), "dx" to v(100), "dy" to v(50), "durationMs" to v(100)),
                            act("CAP-0001", "target" to v("m2"), "dx" to v(100), "dy" to v(0), "durationMs" to v(1000)),
                        ),
                    ),
                ),
            ),
        )
        fx.engine.start()
        assertEquals(4, fx.engine.tweenCount())
        fx.engine.tick(50)
        assertEquals(50f, fx.engine.objectById("m1")!!.transform().x)
        fx.engine.tick(50)
        val t = fx.engine.objectById("m1")!!.transform()
        assertEquals(100f, t.x)
        assertEquals(50f, t.y)
        fx.engine.fireEventById("boot") // restart tweens, then destroy m2 mid-flight
        fx.engine.destroyObject("m2")
        assertNull(fx.engine.objectById("m2"))
        assertTrue(fx.engine.tweenCount() <= 2)
        fx.engine.tick(5000) // completes without crash (re-fired target: 200)
        assertEquals(200f, fx.engine.objectById("m1")!!.transform().x)
    }

    @Test
    fun rotate_scale_setposition_instant() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("o1", "s1", x = 5, y = 5, w = 100, h = 50)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0002", "target" to v("o1"), "angle" to v(90)),
                            act("CAP-0003", "target" to v("o1"), "sx" to v(2), "sy" to v(3)),
                            act("CAP-0006", "target" to v("o1"), "x" to v(11), "y" to v(22)),
                        ),
                    ),
                ),
            ),
        )
        fx.engine.start()
        val tr = fx.engine.objectById("o1")!!.transform()
        assertEquals(90f, tr.rotation)
        assertEquals(200f, tr.w)
        assertEquals(150f, tr.h)
        assertEquals(11f, tr.x)
        assertEquals(22f, tr.y)
    }

    // ---------- spawn / destroy ----------
    @Test
    fun spawn_clones_prefab_and_destroy_removes() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("tpl", "s1", "circle", x = 1, y = 2, w = 30, h = 30)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0005", "prefab" to v("tpl"), "x" to v(300), "y" to v(400)),
                            act("CAP-0004", "target" to v("tpl")),
                            act("CAP-0004", "target" to v("tpl")),
                        ),
                    ),
                ),
            ),
        )
        fx.engine.start()
        assertNull(fx.engine.objectById("tpl"))
        val spawned = fx.engine.objectsInScene("s1")
        assertEquals(1, spawned.size)
        assertEquals("circle", spawned[0].kind)
        assertEquals(300f, spawned[0].transform().x)
        assertEquals(400f, spawned[0].transform().y)
        val w = fx.engine.drainWarnings()
        assertEquals(1, w.size) // second destroy of tpl
        assertTrue(w[0].contains("CAP-0004"))
    }

    // ---------- combat ----------
    @Test
    fun damage_heal_clamp_and_invincible_window() {
        val fx = Fx()
        val hp = buildJsonObject {
            put("max", 100)
            put("current", 60)
            put("invincibleMs", 500)
        }
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(
                    obj("hero", "s1", comps = mapOf("Health" to hp)),
                    obj("plain", "s1"),
                ),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0022", "target" to v("hero"), "amount" to v(20)),
                            act("CAP-0022", "target" to v("hero"), "amount" to v(20)), // invincible: ignored
                            act("CAP-0022", "target" to v("plain"), "amount" to v(5)), // no Health: warns
                        ),
                    ),
                ),
            ),
        )
        fx.engine.start()
        assertEquals(40.0, fx.engine.objectById("hero")!!.health()!!.current, 0.001)
        fx.engine.tick(500)
        fx.engine.fireEventById("boot") // damage again after window... but plain warns again
        assertEquals(20.0, fx.engine.objectById("hero")!!.health()!!.current, 0.001)
        assertTrue(fx.engine.drainWarnings().any { it.contains("no Health") })
        // heal clamps at max (fresh load resets warnings + health)
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("hero", "s1", comps = mapOf("Health" to hp))),
                events = listOf(ev("boot", "start", listOf(act("CAP-0023", "target" to v("hero"), "amount" to v(1000))))),
            ),
        )
        fx.engine.start()
        assertEquals(100.0, fx.engine.objectById("hero")!!.health()!!.current, 0.001)
        assertTrue(fx.engine.drainWarnings().isEmpty())
    }

    @Test
    fun regen_over_time() {
        val fx = Fx()
        val hp = buildJsonObject {
            put("max", 100)
            put("current", 50)
            put("regen", 20)
        }
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("hero", "s1", comps = mapOf("Health" to hp))),
            ),
        )
        fx.engine.start()
        fx.engine.tick(1000)
        assertEquals(70.0, fx.engine.objectById("hero")!!.health()!!.current, 0.001)
        fx.engine.tick(10000)
        assertEquals(100.0, fx.engine.objectById("hero")!!.health()!!.current, 0.001)
    }

    // ---------- save / load ----------
    @Test
    fun save_load_roundtrip_and_bad_slots() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true), scene("s2", false)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0024", "levelId" to v("lv2")),
                            act("CAP-0007", "name" to v("score"), "value" to v(42)),
                            act("CAP-0015", "sceneId" to v("s2")),
                            act("CAP-0018", "slot" to v("slot1")),
                        ),
                    ),
                ),
                vars = listOf(numVar("score")),
                levels = listOf("lv1", "lv2"),
            ),
        )
        fx.engine.start()
        assertNotNull(fx.save.mem["slot1"])
        assertEquals("s2", fx.engine.currentSceneId)
        // mutate, then load back
        fx.engine.vars.set("score", JsonPrimitive(0))
        fx.engine.changeScene("s1", "none")
        fx.engine.unlockedLevels.clear()
        fx.engine.fireEventById("boot") // re-saves current? no: boot re-runs full chain incl. save
        fx.engine.vars.set("score", JsonPrimitive(0))
        fx.engine.loadGame("slot1")
        assertEquals(RtValue.Num(42.0), fx.engine.vars.get("score"))
        assertEquals("s2", fx.engine.currentSceneId)
        assertTrue(fx.engine.unlockedLevels.contains("lv2"))
        // bad slots warn, never crash
        fx.engine.loadGame("empty")
        fx.save.mem["bad"] = "{oops"
        fx.engine.loadGame("bad")
        val w = fx.engine.drainWarnings()
        assertTrue(w.any { it.contains("empty slot") })
        assertTrue(w.any { it.contains("corrupt slot") })
    }

    // ---------- audio / haptic / system ----------
    @Test
    fun play_sound_validates_asset_and_clamps() {
        val fx = Fx()
        val music = buildJsonObject {
            put("assetId", "snd_ok")
            put("volume", 0.5)
            put("autoplay", true)
            put("loop", true)
        }
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("mus", "s1", comps = mapOf("Audio" to music))),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0013", "assetId" to v("snd_ok"), "volume" to v(2)),
                            act("CAP-0013", "assetId" to v("snd_missing")),
                        ),
                    ),
                ),
                assets = listOf("snd_ok"),
            ),
        )
        fx.engine.start()
        // start-events run before scene-enter autoplay; missing asset warns
        assertEquals(2, fx.audio.calls.size)
        assertEquals(RecAudio.Call("snd_ok", 1.0f, false), fx.audio.calls[0])
        assertEquals(RecAudio.Call("snd_ok", 0.5f, true), fx.audio.calls[1])
        assertTrue(fx.engine.drainWarnings().any { it.contains("CAP-0013") && it.contains("snd_missing") })
    }

    @Test
    fun vibrate_link_ui_gravity_camera() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("hero", "s1", x = 10, y = 20)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0026", "durationMs" to v(99999)),
                            act("CAP-0027", "url" to v("https://example.com/x")),
                            act("CAP-0027", "url" to v("not a url")),
                            act("CAP-0016", "screenId" to v("hud")),
                            act("CAP-0017", "screenId" to v("menu")),
                            act("CAP-0031", "gx" to v(0), "gy" to v(9.8)),
                            act("CAP-0032", "target" to v("hero")),
                            act("CAP-0032", "target" to v("ghost")),
                        ),
                    ),
                ),
            ),
        )
        fx.engine.start()
        assertEquals(listOf(5000), fx.haptic.calls)
        assertEquals(listOf("https://example.com/x"), fx.system.calls)
        assertEquals(mapOf("hud" to true, "menu" to false), fx.engine.uiVisible)
        assertEquals(9.8, fx.engine.gravity.gy, 0.0001)
        assertEquals("hero", fx.engine.cameraFollowId)
        val snap = fx.engine.snapshot()
        assertEquals(10f, snap.camera.x)
        assertEquals(20f, snap.camera.y)
        val w = fx.engine.drainWarnings()
        assertTrue(w.any { it.contains("CAP-0027") })
        assertTrue(w.any { it.contains("CAP-0032") })
    }

    @Test
    fun unsupported_capabilities_warn_honestly() {
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                events = listOf(
                    ev(
                        "boot", "start",
                        listOf(
                            act("CAP-0014", "target" to v("o"), "animationId" to v("a")),
                            act("CAP-0025", "checkpointId" to v("c")),
                            act("CAP-0028"),
                            act("CAP-0029", "sku" to v("x")),
                            act("CAP-9999"),
                        ),
                    ),
                ),
            ),
        )
        fx.engine.start()
        val w = fx.engine.drainWarnings()
        assertEquals(5, w.size)
        assertTrue(w.any { it.contains("Phase 4") })
        assertTrue(w.any { it.contains("Phase 8") })
        assertTrue(w.any { it.contains("unknown capability CAP-9999") })
    }

    @Test
    fun nesting_depth_cap_stops_runaway() {
        // 40-deep nested If/then chain must stop at the cap with a warning.
        var inner: JsonObject = act("CAP-0007", "name" to v("deep"), "value" to v(1))
        repeat(40) {
            inner = act(
                "CAP-0009",
                "condition" to cond(v(1), "==", v(1)),
                "then" to JsonArray(listOf(inner)),
            )
        }
        val fx = Fx()
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                events = listOf(ev("boot", "start", listOf(inner))),
                vars = listOf(numVar("deep")),
            ),
        )
        fx.engine.start()
        assertTrue(fx.engine.drainWarnings().any { it.contains("too deep") })
    }

    @Test
    fun play_animation_interpolates_loops_and_validates() {
        val slide = buildJsonObject {
            put("id", "slide")
            put("durationMs", 1000)
            put("loop", true)
            put("frames", JsonArray(listOf(
                buildJsonObject { put("at", 0); put("prop", "x"); put("value", 0) },
                buildJsonObject { put("at", 1000); put("prop", "x"); put("value", 100) },
                buildJsonObject { put("at", 0); put("prop", "opacity"); put("value", 1) },
                buildJsonObject { put("at", 1000); put("prop", "opacity"); put("value", 0) },
            )))
        }
        val once = buildJsonObject {
            put("id", "once")
            put("durationMs", 400)
            put("frames", JsonArray(listOf(
                buildJsonObject { put("at", 0); put("prop", "y"); put("value", 10) },
                buildJsonObject { put("at", 400); put("prop", "y"); put("value", 210) },
            )))
        }
        val base = project(
            scenes = listOf(scene("s1", true)),
            objects = listOf(obj("o1", "s1", x = 5, y = 5)),
            events = listOf(
                ev("boot", "start", listOf(act("CAP-0014", "target" to v("o1"), "animationId" to v("slide")))),
                ev("bad", "start", listOf(
                    act("CAP-0014", "target" to v("ghost"), "animationId" to v("slide")),
                    act("CAP-0014", "target" to v("o1"), "animationId" to v("nope")),
                )),
            ),
        )
        val withAnim = JsonObject(base.toMutableMap().also { it["animations"] = JsonArray(listOf(slide, once)) })
        val fx = Fx()
        fx.engine.load(withAnim)
        fx.engine.start()
        assertEquals(5f, fx.engine.objectById("o1")!!.transform().x) // untouched until first tick
        fx.engine.tick(500)
        assertEquals(50f, fx.engine.objectById("o1")!!.transform().x)
        assertEquals(0.5f, fx.engine.objectById("o1")!!.transform().opacity)
        fx.engine.tick(500) // loop wraps to start
        assertEquals(0f, fx.engine.objectById("o1")!!.transform().x)
        assertEquals(1, fx.engine.animCount())
        // non-loop holds the end value then finishes
        fx.engine.fireEventById("bad") // only warnings, no crash
        assertTrue(fx.engine.drainWarnings().any { it.contains("CAP-0014") })
        fx.engine.playAnimation("o1", "once")
        fx.engine.tick(1000)
        assertEquals(210f, fx.engine.objectById("o1")!!.transform().y)
        assertEquals(0, fx.engine.animCount())
    }

    // ---------- phase 7: particles + shake ----------
    @Test
    fun particles_spawn_age_and_respect_config() {
        val fx = Fx()
        val base = project(scenes = listOf(scene("s1", true)))
        assertEquals(0, Fx().let {
            it.engine.load(base)
            it.engine.start()
            it.engine.tick(1000)
            it.engine.particleCount()
        }) // disabled by default
        val sys = buildJsonObject {
            put("ambientParticles", buildJsonObject {
                put("enabled", true)
                put("ratePerSec", 100)
                put("max", 50)
                put("lifeMs", 2000)
            })
        }
        val withSys = JsonObject(base.toMutableMap().also { it["systems"] = sys })
        fx.engine.load(withSys)
        fx.engine.start()
        assertEquals(0, fx.engine.particleCount())
        fx.engine.tick(1000) // 100 requested, capped at max=50
        assertEquals(50, fx.engine.particleCount())
        assertEquals(50, fx.engine.snapshot().particles.size)
        fx.engine.tick(5000) // all aged out, respawn capped again
        assertTrue(fx.engine.particleCount() in 1..50)
    }

    @Test
    fun camera_shake_fires_and_decays() {
        val fx = Fx()
        fx.engine.load(project(scenes = listOf(scene("s1", true))))
        fx.engine.start()
        val calm = fx.engine.snapshot().camera
        assertEquals(0f, calm.x)
        assertEquals(0f, calm.y)
        fx.engine.addTrauma(1f)
        assertEquals(1f, fx.engine.traumaLevel())
        val shaken = fx.engine.snapshot().camera
        assertTrue(kotlin.math.abs(shaken.x) + kotlin.math.abs(shaken.y) > 0f)
        fx.engine.tick(1000) // full decay
        assertEquals(0f, fx.engine.traumaLevel())
        val rest = fx.engine.snapshot().camera
        assertEquals(0f, rest.x)
        assertEquals(0f, rest.y)
    }

    @Test
    fun damage_fires_shake_and_burst() {
        val fx = Fx()
        val hp = buildJsonObject {
            put("max", 100)
            put("current", 100)
            put("invincibleMs", 0)
        }
        fx.engine.load(
            project(
                scenes = listOf(scene("s1", true)),
                objects = listOf(obj("hero", "s1", comps = mapOf("Health" to hp))),
                events = listOf(ev("boot", "start", listOf(act("CAP-0022", "target" to v("hero"), "amount" to v(10))))),
            ),
        )
        fx.engine.start() // damage fires on boot
        assertTrue(fx.engine.traumaLevel() > 0f)
        assertEquals(10, fx.engine.particleCount()) // exactly one burst, ambient off
        fx.engine.tick(1000) // burst lifetime is 600ms
        assertEquals(0, fx.engine.particleCount())
    }
}
