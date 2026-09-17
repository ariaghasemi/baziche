package com.baziche.gameshell

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Vibrator
import android.os.VibrationEffect
import com.baziche.runtime.AudioSink
import com.baziche.runtime.HapticSink
import com.baziche.runtime.SaveSink
import com.baziche.runtime.SystemSink
import java.io.File

/** Real platform sinks for the offline game shell (no backend, no network). */
class ShellAudioSink(private val context: Context) : AudioSink {
    private val players = mutableListOf<MediaPlayer>()

    override fun play(assetId: String, volume: Float, loop: Boolean) {
        try {
            if (players.size >= 8) return // polyphony cap
            val fd = context.assets.openFd("baziche/assets/$assetId")
            val mp = MediaPlayer()
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
            mp.isLooping = loop
            if (!loop) mp.setOnCompletionListener { it.release(); players.remove(it) } else players.add(mp)
            if (!loop) players.add(mp)
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
            // Missing/corrupt asset: stay silent, game continues.
        }
    }

    fun release() {
        players.forEach { try { it.release() } catch (_: Exception) {} }
        players.clear()
    }
}

class ShellHapticSink(private val context: Context) : HapticSink {
    override fun vibrate(durationMs: Int) {
        try {
            val v = context.getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createOneShot(durationMs.coerceIn(1, 500).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}

class ShellSystemSink(private val context: Context) : SystemSink {
    override fun openLink(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (_: Exception) {
        }
    }
}

class ShellSaveSink(private val context: Context) : SaveSink {
    private fun file(slot: String): File {
        val safe = slot.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
        return File(File(context.filesDir, "saves").apply { mkdirs() }, "$safe.json")
    }

    override fun save(slot: String, data: String) {
        try {
            file(slot).writeText(data)
        } catch (_: Exception) {
        }
    }

    override fun load(slot: String): String? {
        return try {
            val f = file(slot)
            if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            null
        }
    }
}
