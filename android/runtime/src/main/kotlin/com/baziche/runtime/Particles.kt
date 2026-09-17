package com.baziche.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ambient particle simulation + one-shot bursts. Driven by project JSON
 * `systems.ambientParticles` (no new CAPs) and engine events (damage bursts).
 * Deterministic: all randomness flows through the passed [Random].
 */
data class ParticleConfig(
    val enabled: Boolean = false,
    val ratePerSec: Float = 20f,
    val max: Int = 120,
    /** Upward drift speed, logical px/sec. */
    val speed: Float = 40f,
    val size: Float = 4f,
    val color: String = "#FFFFFF",
    val lifeMs: Long = 3000,
    /** Downward acceleration, px/sec^2. */
    val gravity: Float = 0f,
    /** Spawn width multiplier (1 = full scene width). */
    val spread: Float = 1f,
) {
    companion object {
        fun parse(o: JsonObject?): ParticleConfig {
            if (o == null) return ParticleConfig()
            fun num(k: String, d: Float): Float = (o[k] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: d
            return ParticleConfig(
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                ratePerSec = num("ratePerSec", 20f).coerceAtLeast(0f),
                max = (o["max"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 400) ?: 120,
                speed = num("speed", 40f),
                size = num("size", 4f).coerceIn(0.5f, 64f),
                color = (o["color"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("#") } ?: "#FFFFFF",
                lifeMs = (o["lifeMs"] as? JsonPrimitive)?.longOrNull?.coerceIn(100, 30000) ?: 3000,
                gravity = num("gravity", 0f),
                spread = num("spread", 1f).coerceIn(0f, 2f),
            )
        }
    }
}

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var lifeMs: Long,
    val maxLifeMs: Long,
    val size: Float,
    val color: String,
)

data class ParticleDrawable(val x: Float, val y: Float, val size: Float, val color: String, val alpha: Float)

class ParticleSystem {
    private val parts = mutableListOf<Particle>()
    private var spawnDebt = 0f

    val particles: List<Particle> get() = parts

    fun clear() {
        parts.clear()
        spawnDebt = 0f
    }

    fun update(dtMs: Long, cfg: ParticleConfig, w: Float, h: Float, rng: Random) {
        if (dtMs <= 0) return
        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.lifeMs -= dtMs
            if (p.lifeMs <= 0) {
                it.remove()
                continue
            }
            val dt = dtMs / 1000f
            p.vy += cfg.gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
        if (cfg.enabled && parts.size < cfg.max) {
            spawnDebt += cfg.ratePerSec * dtMs / 1000f
            while (spawnDebt >= 1f && parts.size < cfg.max) {
                spawnDebt -= 1f
                parts.add(
                    Particle(
                        x = rng.nextFloat() * w * cfg.spread,
                        y = h + 4f,
                        vx = (rng.nextFloat() - 0.5f) * cfg.speed * 0.4f,
                        vy = -cfg.speed * (0.6f + rng.nextFloat() * 0.8f),
                        lifeMs = cfg.lifeMs,
                        maxLifeMs = cfg.lifeMs,
                        size = cfg.size * (0.6f + rng.nextFloat() * 0.8f),
                        color = cfg.color,
                    ),
                )
            }
            if (parts.size >= cfg.max) spawnDebt = 0f
        }
    }

    fun burst(x: Float, y: Float, count: Int, color: String, speed: Float, lifeMs: Long, size: Float, rng: Random) {
        repeat(count.coerceAtLeast(0)) {
            val a = rng.nextFloat() * 2f * PI.toFloat()
            val s = speed * (0.4f + rng.nextFloat())
            parts.add(Particle(x, y, cos(a) * s, sin(a) * s, lifeMs, lifeMs, size, color))
        }
        while (parts.size > 400) parts.removeAt(0) // hard cap including bursts
    }

    fun drawables(): List<ParticleDrawable> = parts.map {
        ParticleDrawable(it.x, it.y, it.size, it.color, (it.lifeMs.toFloat() / it.maxLifeMs.toFloat()).coerceIn(0f, 1f))
    }
}
