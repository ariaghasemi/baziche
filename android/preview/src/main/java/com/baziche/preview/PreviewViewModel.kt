package com.baziche.preview

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.ProjectRepository
import com.baziche.core.network.BazicheApi
import com.baziche.preview.sinks.FileSaveSink
import com.baziche.preview.sinks.LinkSink
import com.baziche.preview.sinks.SoundPoolAudioSink
import com.baziche.preview.sinks.VibratorSink
import com.baziche.runtime.EngineSinks
import com.baziche.runtime.GameEngine
import com.baziche.runtime.RenderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class PreviewUi(
    val loading: Boolean = true,
    val error: String? = null,
    val snapshot: RenderState? = null,
    val warnings: List<String> = emptyList(),
    val projectName: String = "",
    val orientation: String = "portrait",
    val images: Map<String, ImageBitmap> = emptyMap(),
)

class PreviewViewModel(
    private val repo: ProjectRepository,
    private val api: BazicheApi,
    private val appContext: Context,
    private val projectId: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(PreviewUi())
    val ui: StateFlow<PreviewUi> = _ui

    private var engine: GameEngine? = null
    private var ticker: Job? = null
    private var audioSink: SoundPoolAudioSink? = null
    private val imageCache = mutableMapOf<String, ImageBitmap>()
    private val imageFailed = mutableSetOf<String>()
    private val imageInflight = mutableSetOf<String>()
    private val http = OkHttpClient()

    init {
        load()
    }

    fun load() {
        ticker?.cancel()
        audioSink?.release()
        audioSink = null
        engine = null
        imageCache.clear()
        imageFailed.clear()
        imageInflight.clear()
        viewModelScope.launch {
            _ui.value = PreviewUi(loading = true)
            val detail = when (val r = repo.getProject(projectId)) {
                is ApiResult.Success -> r.data
                is ApiResult.Error -> {
                    _ui.value = PreviewUi(loading = false, error = r.message)
                    return@launch
                }
            }
            val json = detail.json
            if (json == null) {
                _ui.value = PreviewUi(loading = false, error = "empty project")
                return@launch
            }
            val assetMap = resolveAssets(json)
            val audio = SoundPoolAudioSink(appContext) { serverId -> downloadAsset(serverId, "preview_audio") }
            audio.setMapping(assetMap)
            audioSink = audio
            val eng = try {
                GameEngine(EngineSinks(audio, VibratorSink(appContext), LinkSink(appContext), FileSaveSink(appContext, projectId))).also {
                    it.load(json)
                    it.start()
                }
            } catch (t: Throwable) {
                _ui.value = PreviewUi(loading = false, error = t.message ?: "load failed")
                return@launch
            }
            engine = eng
            val snap = eng.snapshot()
            _ui.value = PreviewUi(
                loading = false,
                snapshot = snap,
                warnings = eng.drainWarnings(),
                projectName = eng.projectName,
                orientation = eng.orientation,
            )
            launchImageLoads(snap, assetMap)
            ticker = viewModelScope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    eng.tick(TICK_MS)
                    val s = eng.snapshot()
                    _ui.value = _ui.value.copy(
                        snapshot = s,
                        warnings = (_ui.value.warnings + eng.drainWarnings()).takeLast(MAX_WARNINGS),
                    )
                    launchImageLoads(s, assetMap)
                }
            }
        }
    }

    fun tap(x: Float, y: Float) {
        val eng = engine ?: return
        eng.tap(x, y)
        _ui.value = _ui.value.copy(
            snapshot = eng.snapshot(),
            warnings = (_ui.value.warnings + eng.drainWarnings()).takeLast(MAX_WARNINGS),
        )
    }

    fun restart() {
        val eng = engine ?: return
        try {
            eng.start()
        } catch (t: Throwable) {
            _ui.value = _ui.value.copy(error = t.message)
            return
        }
        _ui.value = _ui.value.copy(snapshot = eng.snapshot(), warnings = eng.drainWarnings())
    }

    fun dismissWarnings() {
        _ui.value = _ui.value.copy(warnings = emptyList())
    }

    /** Kicks off async bitmap loads for sprite assets visible in [snap]. Cheap: skips cached/failed. */
    private fun launchImageLoads(snap: RenderState, assetMap: Map<String, String>) {
        if (imageCache.size >= MAX_IMAGES) return
        val wanted = snap.drawables
            .filter { it.kind == "sprite" }
            .mapNotNull { it.sprite?.assetId }
            .distinct()
            .filter { it !in imageCache && it !in imageFailed && it !in imageInflight }
            .take(4)
        for (assetId in wanted) {
            val serverId = assetMap[assetId] ?: run { imageFailed.add(assetId); continue }
            imageInflight.add(assetId)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val file = downloadAsset(serverId, "preview_images")
                    val bmp = file?.absolutePath?.let {
                        BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 })
                    }?.asImageBitmap()
                    if (bmp != null) {
                        imageCache[assetId] = bmp
                        _ui.value = _ui.value.copy(images = imageCache.toMap())
                    } else {
                        imageFailed.add(assetId)
                    }
                } catch (_: Exception) {
                    imageFailed.add(assetId)
                } finally {
                    imageInflight.remove(assetId)
                }
            }
        }
    }

    /** Maps project-JSON asset ids to server asset ids by hash. Best-effort: never fails load. */
    private suspend fun resolveAssets(json: JsonObject): Map<String, String> {
        return try {
            val server = withContext(Dispatchers.IO) { api.assets(projectId).assets }
            val byHash = server.associate { it.hash.lowercase() to it.id }
            val out = mutableMapOf<String, String>()
            (json["assets"] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                val hash = (o["hash"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return@forEach
                byHash[hash]?.let { out[id] = it }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Downloads one asset via presigned URL into cacheDir (size-capped). Null on any failure. */
    private suspend fun downloadAsset(serverId: String, subdir: String): File? = withContext(Dispatchers.IO) {
        try {
            val url = api.assetUrl(serverId).url ?: return@withContext null
            val file = File(appContext.cacheDir, "$subdir/$projectId/$serverId.bin")
            if (file.exists() && file.length() > 0) return@withContext file
            file.parentFile?.mkdirs()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                if (body.contentLength() > MAX_ASSET_BYTES) return@withContext null
                var total = 0L
                val buf = ByteArray(8192)
                val input = body.byteStream()
                file.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ASSET_BYTES) {
                            out.close()
                            file.delete()
                            return@withContext null
                        }
                        out.write(buf, 0, n)
                    }
                }
                file
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        audioSink?.release()
        audioSink = null
    }

    companion object {
        const val TICK_MS = 50L
        const val MAX_WARNINGS = 20
        const val MAX_IMAGES = 32
        const val MAX_ASSET_BYTES = 8L * 1024 * 1024
    }
}
