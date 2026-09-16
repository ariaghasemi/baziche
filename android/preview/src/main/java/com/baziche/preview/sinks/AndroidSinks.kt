package com.baziche.preview.sinks

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import com.baziche.runtime.AudioSink
import com.baziche.runtime.HapticSink
import com.baziche.runtime.SaveSink
import com.baziche.runtime.SystemSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * SoundPool-backed audio. Downloads are lazy per asset id; looped music is
 * single-flight (a new loop stops the previous one — one BGM at a time).
 */
class SoundPoolAudioSink(
    appContext: Context,
    private val download: suspend (serverAssetId: String) -> File?,
) : AudioSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var mapping: Map<String, String> = emptyMap()
    private val loaded = mutableMapOf<String, Int>()
    private val loading = mutableSetOf<String>()
    private val pendingPlay = mutableMapOf<Int, Triple<String, Float, Boolean>>()
    private val activeLoops = mutableMapOf<String, Int>()
    private var released = false

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (!handleLoadComplete(sampleId, status)) {
                // Listener beat our registration (rare race): retry once shortly.
                scope.launch {
                    delay(200)
                    handleLoadComplete(sampleId, status)
                }
            }
        }
    }

    fun setMapping(m: Map<String, String>) {
        synchronized(this) { mapping = m }
    }

    override fun play(assetId: String, volume: Float, loop: Boolean) {
        synchronized(this) {
            if (released) return
            loaded[assetId]?.let {
                doPlay(assetId, it, volume, loop)
                return
            }
            if (!loading.add(assetId)) return
        }
        val serverId = synchronized(this) { mapping[assetId] }
        if (serverId == null) {
            synchronized(this) { loading.remove(assetId) }
            return
        }
        scope.launch {
            val file = try {
                download(serverId)
            } catch (_: Exception) {
                null
            }
            if (file == null || !file.exists()) {
                synchronized(this@SoundPoolAudioSink) { loading.remove(assetId) }
                return@launch
            }
            val sampleId = try {
                pool.load(file.absolutePath, 1)
            } catch (_: Exception) {
                0
            }
            if (sampleId == 0) {
                synchronized(this@SoundPoolAudioSink) { loading.remove(assetId) }
                return@launch
            }
            synchronized(this@SoundPoolAudioSink) { pendingPlay[sampleId] = Triple(assetId, volume, loop) }
        }
    }

    private fun handleLoadComplete(sampleId: Int, status: Int): Boolean {
        synchronized(this) {
            val req = pendingPlay.remove(sampleId) ?: return false
            if (status == 0 && !released) {
                loaded[req.first] = sampleId
                loading.remove(req.first)
                doPlay(req.first, sampleId, req.second, req.third)
            } else {
                loading.remove(req.first)
            }
            return true
        }
    }

    /** Must be called under lock. */
    private fun doPlay(assetId: String, soundId: Int, volume: Float, loop: Boolean) {
        if (loop) {
            if (activeLoops.containsKey(assetId)) return // already looping: don't restart
            for ((_, stream) in activeLoops) {
                try {
                    pool.stop(stream)
                } catch (_: Exception) {
                }
            }
            activeLoops.clear()
            val stream = try {
                pool.play(soundId, volume, volume, 1, -1, 1f)
            } catch (_: Exception) {
                0
            }
            if (stream != 0) activeLoops[assetId] = stream
        } else {
            try {
                pool.play(soundId, volume, volume, 1, 0, 1f)
            } catch (_: Exception) {
            }
        }
    }

    fun release() {
        synchronized(this) { released = true }
        scope.cancel()
        try {
            pool.release()
        } catch (_: Exception) {
        }
    }
}

class VibratorSink(appContext: Context) : HapticSink {
    private val vib = appContext.getSystemService(Vibrator::class.java)

    override fun vibrate(durationMs: Int) {
        try {
            vib?.vibrate(VibrationEffect.createOneShot(durationMs.toLong().coerceAtLeast(0), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}

class LinkSink(private val appContext: Context) : SystemSink {
    override fun openLink(url: String) {
        try {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

/** Per-project save slots under filesDir. Slot names are sanitized (no traversal). */
class FileSaveSink(appContext: Context, projectId: String) : SaveSink {
    private val dir = File(appContext.filesDir, "preview_saves/$projectId")

    override fun save(slot: String, data: String) {
        try {
            dir.mkdirs()
            File(dir, "${safeSlot(slot)}.json").writeText(data)
        } catch (_: Exception) {
        }
    }

    override fun load(slot: String): String? {
        return try {
            File(dir, "${safeSlot(slot)}.json").takeIf { it.exists() }?.readText()
        } catch (_: Exception) {
            null
        }
    }

    private fun safeSlot(slot: String): String =
        slot.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64).ifEmpty { "auto" }
}
