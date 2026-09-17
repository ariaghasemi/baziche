package com.baziche.scaffolder

import com.baziche.runtime.GameEngine
import com.baziche.runtime.NoopSinks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaffolderTest {
    @Test
    fun same_request_same_bytes() {
        val req = ScaffoldRequest(gameType = "runner", theme = "ocean", seed = 42)
        assertEquals(Scaffolder.generate(req).toString(), Scaffolder.generate(req).toString())
    }

    @Test
    fun different_seeds_differ() {
        val a = Scaffolder.generate(ScaffoldRequest(seed = 1)).toString()
        val b = Scaffolder.generate(ScaffoldRequest(seed = 2)).toString()
        assertNotEquals(a, b)
    }

    @Test
    fun all_game_types_validate_and_run() {
        for (type in Scaffolder.GAME_TYPES.keys) {
            val json = Scaffolder.generate(ScaffoldRequest(gameType = type, seed = 7, sceneCount = 3, difficulty = 3))
            assertEquals("type $type: ${Scaffolder.validate(json)}", emptyList<String>(), Scaffolder.validate(json))
            // Proves runtime compatibility: loads, starts, ticks, snapshots.
            val engine = GameEngine(NoopSinks.ALL)
            engine.load(json)
            assertEquals(type, engine.gameType)
            engine.start()
            engine.tick(500)
            val snap = engine.snapshot()
            assertTrue("type $type renders nothing", snap.drawables.isNotEmpty())
        }
    }

    @Test
    fun unknown_type_and_theme_fall_back_cleanly() {
        val json = Scaffolder.generate(ScaffoldRequest(gameType = "nope", theme = "nope", seed = 1))
        assertEquals(emptyList<String>(), Scaffolder.validate(json))
        val meta = json["meta"] as JsonObject
        assertEquals("quiz", (meta["gameType"] as JsonPrimitive).content)
    }

    @Test
    fun difficulty_scales_content_and_structure_is_sound() {
        val easy = Scaffolder.generate(ScaffoldRequest(difficulty = 1, seed = 5))
        val hard = Scaffolder.generate(ScaffoldRequest(difficulty = 3, seed = 5))
        val nEasy = (easy["objects"] as JsonArray).size
        val nHard = (hard["objects"] as JsonArray).size
        assertTrue("easy=$nEasy hard=$nHard", nHard > nEasy)
        // every event action capability is in range (belt & braces behind validate())
        val events = hard["events"] as JsonArray
        assertTrue(events.isNotEmpty())
        for (e in events.map { it as JsonObject }) {
            for (a in ((e["actions"] as JsonArray).map { it as JsonObject })) {
                val cap = ((a["capability"] as JsonPrimitive).contentOrNull ?: "").removePrefix("CAP-").toInt()
                assertTrue(cap in 1..32)
            }
        }
    }
}
