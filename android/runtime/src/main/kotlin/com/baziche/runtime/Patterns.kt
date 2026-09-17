package com.baziche.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/**
 * Inventory / quest / shop as pure functions over [VariableStore] — no new CAPs.
 * Games drive them with CAP-0007 (Set Variable), CAP-0009 (If) and CAP-0010
 * (Compare); hosts (shop screens, HUD) may also call these directly.
 * See docs/GAME_PATTERNS.md for the JSON conventions.
 */
object Inventory {
    /** Slot var holds `{"itemId": count, ...}` (a STRING variable). */
    fun get(store: VariableStore, slot: String): Map<String, Int> {
        val raw = (store.get(slot) as? RtValue.Str)?.v ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return try {
            val o = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            o.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { k to it } }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun countOf(store: VariableStore, slot: String, item: String): Int = get(store, slot)[item] ?: 0

    fun encode(inv: Map<String, Int>): String =
        buildJsonObject { for ((k, v) in inv) put(k, JsonPrimitive(v)) }.toString()

    /** False when slot var is missing/untyped (game JSON must declare it as STRING). */
    fun add(store: VariableStore, slot: String, item: String, n: Int = 1): Boolean {
        if (n <= 0 || item.isBlank()) return false
        if (store.get(slot) !is RtValue.Str) return false
        val m = get(store, slot).toMutableMap()
        m[item] = (m[item] ?: 0) + n
        return store.set(slot, JsonPrimitive(encode(m)))
    }

    /** False when missing var OR insufficient count (no partial removal). */
    fun remove(store: VariableStore, slot: String, item: String, n: Int = 1): Boolean {
        if (n <= 0 || item.isBlank()) return false
        if (store.get(slot) !is RtValue.Str) return false
        val m = get(store, slot).toMutableMap()
        val have = m[item] ?: 0
        if (have < n) return false
        val left = have - n
        if (left == 0) m.remove(item) else m[item] = left
        return store.set(slot, JsonPrimitive(encode(m)))
    }
}

object Quests {
    /** Quest stage lives in NUMBER var `quest_<id>` (0 = not started). */
    fun varName(id: String): String = "quest_$id"

    fun stage(store: VariableStore, id: String): Int = VariableStore.asNumber(store.get(varName(id)) ?: RtValue.Num(0.0)).toInt()

    fun setStage(store: VariableStore, id: String, stage: Int): Boolean =
        store.setValue(varName(id), RtValue.Num(stage.toDouble()))

    /** Returns the new stage, or -1 when the var is missing. */
    fun advance(store: VariableStore, id: String): Int {
        if (store.get(varName(id)) == null) return -1
        val next = stage(store, id) + 1
        return if (setStage(store, id, next)) next else -1
    }

    fun isAtLeast(store: VariableStore, id: String, stage: Int): Boolean = stage(store, id) >= stage
}

object Shop {
    /**
     * Buy [qty]×[item] for [price] of [currencyVar] into [invSlot].
     * Atomic: currency is refunded if the inventory write fails.
     */
    fun buy(store: VariableStore, currencyVar: String, price: Double, item: String, invSlot: String, qty: Int = 1): Boolean {
        if (qty <= 0 || price < 0 || item.isBlank()) return false
        val bal = store.get(currencyVar) ?: return false
        val total = price * qty
        if (VariableStore.asNumber(bal) < total) return false
        if (!store.setValue(currencyVar, RtValue.Num(VariableStore.asNumber(bal) - total))) return false
        if (!Inventory.add(store, invSlot, item, qty)) {
            store.setValue(currencyVar, bal) // refund
            return false
        }
        return true
    }

    /**
     * Sell [qty]×[item] from [invSlot] for [price] each into [currencyVar].
     * Atomic: items are restored if the currency write fails.
     */
    fun sell(store: VariableStore, invSlot: String, item: String, price: Double, currencyVar: String, qty: Int = 1): Boolean {
        if (qty <= 0 || price < 0 || item.isBlank()) return false
        if (!Inventory.remove(store, invSlot, item, qty)) return false
        val bal = store.get(currencyVar)
        if (bal == null || !store.setValue(currencyVar, RtValue.Num(VariableStore.asNumber(bal) + price * qty))) {
            Inventory.add(store, invSlot, item, qty) // rollback
            return false
        }
        return true
    }
}
