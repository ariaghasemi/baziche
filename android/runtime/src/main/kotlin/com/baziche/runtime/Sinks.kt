package com.baziche.runtime

/**
 * Ports from the pure runtime to the platform. :preview provides Android
 * implementations; unit tests use recording fakes; :game-shell reuses them.
 */
interface AudioSink {
    fun play(assetId: String, volume: Float, loop: Boolean)
}

interface HapticSink {
    fun vibrate(durationMs: Int)
}

interface SystemSink {
    fun openLink(url: String)
}

interface SaveSink {
    fun save(slot: String, data: String)
    fun load(slot: String): String?
}

data class EngineSinks(
    val audio: AudioSink,
    val haptic: HapticSink,
    val system: SystemSink,
    val save: SaveSink,
)

/** Silent sinks for headless use (tests that don't assert side effects). */
object NoopSinks {
    val ALL = EngineSinks(
        audio = object : AudioSink { override fun play(assetId: String, volume: Float, loop: Boolean) = Unit },
        haptic = object : HapticSink { override fun vibrate(durationMs: Int) = Unit },
        system = object : SystemSink { override fun openLink(url: String) = Unit },
        save = object : SaveSink {
            private val mem = mutableMapOf<String, String>()
            override fun save(slot: String, data: String) { mem[slot] = data }
            override fun load(slot: String): String? = mem[slot]
        },
    )
}
