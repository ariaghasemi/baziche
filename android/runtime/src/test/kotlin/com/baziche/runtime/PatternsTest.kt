package com.baziche.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternsTest {
    private fun store(): VariableStore = VariableStore(
        listOf(
            RtVarDef("inventory", VarType.STRING, RtValue.Str("{}"), false),
            RtVarDef("coins", VarType.NUMBER, RtValue.Num(100.0), false),
            RtVarDef("quest_rescue", VarType.NUMBER, RtValue.Num(0.0), false),
        ),
    )

    @Test
    fun inventory_add_remove_counts() {
        val s = store()
        assertTrue(Inventory.add(s, "inventory", "sword"))
        assertTrue(Inventory.add(s, "inventory", "potion", 5))
        assertEquals(1, Inventory.countOf(s, "inventory", "sword"))
        assertEquals(5, Inventory.countOf(s, "inventory", "potion"))
        assertTrue(Inventory.remove(s, "inventory", "potion", 2))
        assertEquals(3, Inventory.countOf(s, "inventory", "potion"))
        assertFalse(Inventory.remove(s, "inventory", "potion", 9)) // no partial removal
        assertEquals(3, Inventory.countOf(s, "inventory", "potion"))
        assertTrue(Inventory.remove(s, "inventory", "sword"))
        assertEquals(0, Inventory.countOf(s, "inventory", "sword"))
        assertEquals(mapOf("potion" to 3), Inventory.get(s, "inventory"))
    }

    @Test
    fun inventory_rejects_missing_slot_and_garbage() {
        val s = store()
        assertFalse(Inventory.add(s, "nope", "x"))
        assertEquals(0, Inventory.countOf(s, "nope", "x"))
        s.setValue("inventory", RtValue.Str("not-json{{"))
        assertEquals(emptyMap<String, Int>(), Inventory.get(s, "inventory"))
        assertTrue(Inventory.add(s, "inventory", "x")) // recovers by overwrite
        assertEquals(1, Inventory.countOf(s, "inventory", "x"))
    }

    @Test
    fun quest_stage_lifecycle() {
        val s = store()
        assertEquals(0, Quests.stage(s, "rescue"))
        assertEquals(1, Quests.advance(s, "rescue"))
        assertTrue(Quests.setStage(s, "rescue", 5))
        assertTrue(Quests.isAtLeast(s, "rescue", 5))
        assertFalse(Quests.isAtLeast(s, "rescue", 6))
        assertEquals(-1, Quests.advance(s, "unknown"))
    }

    @Test
    fun shop_buy_sell_atomic() {
        val s = store()
        assertTrue(Shop.buy(s, "coins", 50.0, "sword", "inventory"))
        assertEquals(50.0, VariableStore.asNumber(s.get("coins")!!), 0.001)
        assertEquals(1, Inventory.countOf(s, "inventory", "sword"))
        assertFalse(Shop.buy(s, "coins", 60.0, "shield", "inventory")) // insufficient
        assertEquals(50.0, VariableStore.asNumber(s.get("coins")!!), 0.001)
        assertTrue(Shop.sell(s, "inventory", "sword", 20.0, "coins"))
        assertEquals(70.0, VariableStore.asNumber(s.get("coins")!!), 0.001)
        assertFalse(Shop.sell(s, "inventory", "sword", 20.0, "coins")) // nothing left
        // broken inventory slot -> currency untouched (rollback)
        assertFalse(Shop.buy(s, "coins", 10.0, "x", "nope"))
        assertEquals(70.0, VariableStore.asNumber(s.get("coins")!!), 0.001)
    }
}
