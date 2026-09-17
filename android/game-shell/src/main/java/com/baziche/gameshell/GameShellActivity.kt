package com.baziche.gameshell

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.baziche.preview.ui.GameCanvas
import com.baziche.runtime.EngineSinks
import com.baziche.runtime.GameEngine
import com.baziche.runtime.RenderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Offline game player: runs assets/baziche/game.json (injected by game-build.yml)
 * with the shared runtime + GameCanvas. No account, no network.
 */
class GameShellActivity : ComponentActivity() {
    private var audio: ShellAudioSink? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ctx = LocalContext.current
                    var engine by remember { mutableStateOf<GameEngine?>(null) }
                    var snap by remember { mutableStateOf(RenderState.EMPTY) }
                    var images by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
                    var orientation by remember { mutableStateOf("portrait") }
                    var error by remember { mutableStateOf<String?>(null) }

                    LaunchedEffect(Unit) {
                        try {
                            val raw = withContext(Dispatchers.IO) { readGameJson() }
                            val root = Json.parseToJsonElement(raw) as? JsonObject
                                ?: throw IllegalArgumentException("game.json is not a JSON object")
                            val audioSink = ShellAudioSink(ctx).also { audio = it }
                            val eng = GameEngine(
                                EngineSinks(audioSink, ShellHapticSink(ctx), ShellSystemSink(ctx), ShellSaveSink(ctx)),
                            )
                            eng.load(root)
                            eng.start()
                            engine = eng
                            orientation = eng.orientation
                            images = withContext(Dispatchers.IO) { loadSprites(spriteIds(root)) }
                            while (true) {
                                eng.tick(16)
                                snap = eng.snapshot()
                                delay(16)
                            }
                        } catch (t: Throwable) {
                            error = t.message ?: "load failed"
                        }
                    }

                    val err = error
                    if (err != null) {
                        Box(Modifier.fillMaxSize()) { Text("Game failed to load: $err") }
                    } else {
                        GameCanvas(snap, orientation, images, onTap = { x, y -> engine?.tap(x, y) })
                    }
                }
            }
        }
    }

    private fun readGameJson(): String {
        return try {
            assets.open("baziche/game.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            // Fallback for hand-built APKs that place game.json at assets root.
            assets.open("game.json").bufferedReader().use { it.readText() }
        }
    }

    /** Asset IDs declared by game.json (sprites + audio); missing files render as placeholders. */
    private fun spriteIds(root: JsonObject): Set<String> {
        val arr = root["assets"] as? JsonArray ?: return emptySet()
        return arr.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.toSet()
    }

    private fun loadSprites(assetIds: Set<String>): Map<String, ImageBitmap> {
        val out = mutableMapOf<String, ImageBitmap>()
        for (id in assetIds.take(32)) {
            try {
                assets.open("baziche/assets/$id").use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream) ?: return@use
                    out[id] = bmp.asImageBitmap()
                }
            } catch (_: Exception) {
                // Missing sprite: GameCanvas renders a tinted placeholder.
            }
        }
        return out
    }

    override fun onDestroy() {
        audio?.release()
        audio = null
        super.onDestroy()
    }
}
