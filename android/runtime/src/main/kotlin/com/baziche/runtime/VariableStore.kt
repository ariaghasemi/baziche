package com.baziche.runtime

import com.baziche.runtime.RtValue.Bool
import com.baziche.runtime.RtValue.Num
import com.baziche.runtime.RtValue.Str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Typed variable storage + `$name` references + `$last` (result of Get/Compare). */
class VariableStore(defs: List<RtVarDef>) {
    private val types: Map<String, VarType> = defs.associate { it.name to it.type }
    private val values: MutableMap<String, RtValue> = defs.associate { it.name to it.initial }.toMutableMap()

    /** Result of the last CAP-0008/0010 within the running event chain. */
    var last: RtValue? = null

    fun get(name: String): RtValue? = values[name]

    /** Sets from raw JSON, coerced to the declared type. False when unknown. */
    fun set(name: String, el: JsonElement): Boolean {
        val t = types[name] ?: return false
        values[name] = coerce(t, el)
        return true
    }

    fun setValue(name: String, v: RtValue): Boolean {
        val t = types[name] ?: return false
        values[name] = convert(t, v)
        return true
    }

    fun snapshotAll(): Map<String, JsonElement> = values.mapValues { it.value.asJson() }

    fun restore(map: Map<String, JsonElement>) {
        for ((k, v) in map) set(k, v)
    }

    companion object {
        fun literal(el: JsonElement): RtValue {
            val p = el as? JsonPrimitive ?: return Str(el.toString())
            p.booleanOrNull?.let { return Bool(it) }
            if (!p.isString) p.doubleOrNull?.let { return Num(it) }
            return Str(p.content)
        }

        fun asNumber(v: RtValue): Double = when (v) {
            is Num -> v.v
            is Bool -> if (v.v) 1.0 else 0.0
            is Str -> v.v.toDoubleOrNull() ?: 0.0
        }

        fun asString(v: RtValue): String = when (v) {
            is Str -> v.v
            is Bool -> v.v.toString()
            is Num -> if (v.v == v.v.toLong().toDouble()) v.v.toLong().toString() else v.v.toString()
        }

        fun asBool(v: RtValue): Boolean = when (v) {
            is Bool -> v.v
            is Num -> v.v != 0.0
            is Str -> v.v.equals("true", ignoreCase = true) || v.v == "1"
        }

        fun coerce(t: VarType, el: JsonElement): RtValue = convert(t, literal(el))

        fun convert(t: VarType, v: RtValue): RtValue = when (t) {
            VarType.NUMBER -> Num(asNumber(v))
            VarType.STRING -> Str(asString(v))
            VarType.BOOL -> Bool(asBool(v))
        }
    }
}

/** Conditions: `{ a, op, b }` where any side may be a `$variable` reference. */
object Conditions {
    fun resolve(el: JsonElement?, store: VariableStore): RtValue? {
        if (el == null || el is JsonNull) return null
        if (el is JsonPrimitive && el.isString) {
            val s = el.content
            if (s.startsWith("$") && s.length > 1) {
                val name = s.drop(1)
                return if (name == "last") store.last else store.get(name)
            }
        }
        return VariableStore.literal(el)
    }

    fun eval(cond: JsonObject, store: VariableStore): Boolean {
        val op = cond.str("op", "==")
        val a = resolve(cond["a"], store) ?: return false
        val b = resolve(cond["b"], store) ?: return false
        if (a is Num && b is Num) {
            return when (op) {
                "==" -> a.v == b.v
                "!=" -> a.v != b.v
                ">" -> a.v > b.v
                "<" -> a.v < b.v
                ">=" -> a.v >= b.v
                "<=" -> a.v <= b.v
                else -> false
            }
        }
        // Non-numeric: only equality is defined.
        return when (op) {
            "==" -> a == b
            "!=" -> a != b
            else -> false
        }
    }
}
