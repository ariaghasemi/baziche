package com.baziche.editor

import com.baziche.editor.core.EditorDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sampleDoc(): EditorDoc = EditorDoc(
    Json.parseToJsonElement(
        """
        {
          "formatVersion": 1,
          "meta": {"name": "T", "gameType": "quiz", "orientation": "portrait"},
          "scenes": [{"id": "s1", "name": "Main", "entry": true, "background": {"color": "#121212"}, "objectIds": [], "transitions": []}],
          "objects": [{"id": "o1", "sceneId": "s1", "kind": "rect", "visible": true, "layer": 0,
                       "components": {"Transform": {"x": 10, "y": 20, "w": 100, "h": 50, "rotation": 0, "opacity": 1}}}],
          "events": [], "variables": [], "assets": []
        }
        """.trimIndent(),
    ).jsonObject,
)

class EditorDocTest {
    @Test
    fun scenes_crud() {
        val d = sampleDoc()
        assertEquals(1, d.scenes().size)
        val s2 = d.addScene("Second")
        assertEquals(2, d.scenes().size)
        assertTrue(d.renameScene(s2.id, "Two"))
        assertTrue(d.setEntry(s2.id))
        assertTrue(d.scenes().first { it.id == s2.id }.entry)
        assertTrue(d.duplicateScene(s2.id) != null)
        assertEquals(3, d.scenes().size)
        assertTrue(d.deleteScene(s2.id))
        // cannot delete the last scene
        val remaining = d.scenes()
        assertTrue(d.deleteScene(remaining[0].id))
        assertEquals(1, d.scenes().size)
        assertFalse(d.deleteScene(d.scenes()[0].id))
    }

    @Test
    fun objects_crud_and_transform() {
        val d = sampleDoc()
        assertEquals(1, d.objectsIn("s1").size)
        val o = d.addObject("s1", "circle", 5f, 6f)!!
        assertEquals("circle", o.kind)
        assertEquals(2, d.objectsIn("s1").size)
        val t = o.transform.copy(x = 42f, opacity = 0.5f)
        assertTrue(d.updateTransform(o.id, t))
        assertEquals(42f, d.getObject(o.id)!!.transform.x)
        assertEquals(0.5f, d.getObject(o.id)!!.transform.opacity)
        assertTrue(d.updateObject(o.id, visible = false))
        assertFalse(d.getObject(o.id)!!.visible)
        assertTrue(d.deleteObject(o.id))
        assertNull(d.getObject(o.id))
    }

    @Test
    fun deleteScene_cascades_objects() {
        val d = sampleDoc()
        val s2 = d.addScene("S2")
        d.addObject(s2.id, "rect", 0f, 0f)
        assertTrue(d.deleteScene(s2.id))
        assertTrue(d.objectsIn(s2.id).isEmpty())
        // original objects untouched
        assertEquals(1, d.objectsIn("s1").size)
    }

    @Test
    fun unknown_ids_are_safe() {
        val d = sampleDoc()
        assertFalse(d.renameScene("nope", "x"))
        assertFalse(d.deleteScene("nope"))
        assertNull(d.duplicateScene("nope"))
        assertFalse(d.setEntry("nope"))
        assertFalse(d.moveScene("nope", 1))
        assertNull(d.addObject("nope", "rect", 0f, 0f))
        assertFalse(d.updateTransform("nope", com.baziche.editor.core.ObjTransform()))
        assertFalse(d.updateObject("nope", visible = true))
        assertFalse(d.deleteObject("nope"))
        assertNotNull(d.toJson()["meta"])
        assertEquals("T", d.projectName())
    }

    @Test
    fun moved_scene_keeps_order() {
        val d = sampleDoc()
        val a = d.addScene("A")
        d.addScene("B")
        assertTrue(d.moveScene(a.id, 1))
        val names = d.scenes().map { it.name }
        assertEquals(listOf("Main", "B", "A"), names)
        assertFalse(d.moveScene(a.id, 1)) // already last
    }

    @Test
    fun blank_scene_name_falls_back() {
        val d = sampleDoc()
        val s = d.addScene("")
        assertTrue(s.name.isNotBlank())
        assertFalse(d.renameScene(s.id, "  "))
    }

    @Test
    fun entry_survives_entry_deletion() {
        val d = sampleDoc()
        d.addScene("S2")
        assertTrue(d.deleteScene("s1"))
        assertTrue(d.scenes().any { it.entry })
    }
}
