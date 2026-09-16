package com.baziche.preview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baziche.preview.PreviewViewModel
import com.baziche.preview.R
import com.baziche.runtime.Drawable
import com.baziche.runtime.RenderState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(vm: PreviewViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.projectName.ifBlank { stringResource(R.string.preview) }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
                actions = {
                    TextButton(onClick = { vm.restart() }, enabled = !ui.loading && ui.error == null) {
                        Text(stringResource(R.string.restart))
                    }
                },
            )
        },
    ) { pad ->
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error!!)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
                }
            }
            ui.snapshot != null -> Column(Modifier.fillMaxSize().padding(pad)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    GameCanvas(ui.snapshot!!, ui.orientation, ui.images) { x, y -> vm.tap(x, y) }
                }
                if (ui.warnings.isNotEmpty()) {
                    WarningsCard(ui.warnings) { vm.dismissWarnings() }
                }
            }
        }
    }
}

@Composable
private fun WarningsCard(warnings: List<String>, onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(8.dp).clickable { onDismiss() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.warnings_title),
                style = MaterialTheme.typography.labelLarge,
            )
            warnings.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (warnings.size > 3) Text(stringResource(R.string.warnings_more, warnings.size - 3))
        }
    }
}

/**
 * Shared game canvas (used by :preview and :game-shell).
 * [images] maps Sprite assetId -> decoded bitmap; missing entries render a tinted placeholder.
 */
@Composable
fun GameCanvas(snap: RenderState, orientation: String, images: Map<String, ImageBitmap>, onTap: (Float, Float) -> Unit) {
    val logical = remember(orientation) {
        if (orientation == "landscape") Size(800f, 480f) else Size(480f, 800f)
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var alpha by remember(snap.sceneId) { mutableStateOf(0f) }
    LaunchedEffect(snap.sceneId) {
        repeat(10) {
            alpha = (it + 1) / 10f
            delay(25)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val scale = remember(maxWidth, maxHeight, logical) {
            minOf(
                with(density) { maxWidth.toPx() } / logical.width,
                with(density) { maxHeight.toPx() } / logical.height,
            )
        }
        val camX = if (snap.camera.followId != null) (snap.camera.x - logical.width / 2f).coerceAtLeast(0f) else 0f
        val camY = if (snap.camera.followId != null) (snap.camera.y - logical.height / 2f).coerceAtLeast(0f) else 0f
        val drawables = snap.drawables
        Canvas(
            Modifier
                .width(with(density) { (logical.width * scale).toDp() })
                .height(with(density) { (logical.height * scale).toDp() })
                .align(Alignment.Center)
                .alpha(alpha)
                .pointerInput(scale, camX, camY, snap.sceneId) {
                    detectTapGestures { tap -> onTap(tap.x / scale + camX, tap.y / scale + camY) }
                },
        ) {
            drawRect(parseColor(snap.bgColor), size = size)
            for (d in drawables) {
                val left = (d.x - camX) * scale
                val top = (d.y - camY) * scale
                val w = d.w * scale
                val h = d.h * scale
                val pivot = Offset(left + w / 2f, top + h / 2f)
                withTransform({
                    rotate(d.rotation, pivot)
                    if (d.kind == "sprite") {
                        scale(
                            if (d.sprite?.flipX == true) -1f else 1f,
                            if (d.sprite?.flipY == true) -1f else 1f,
                            pivot,
                        )
                    }
                }) {
                    when (d.kind) {
                        "circle" -> drawOval(kindColor(d), Offset(left, top), Size(w, h), alpha = d.opacity)
                        "text" -> {
                            val t = d.text
                            if (t != null) {
                                drawText(
                                    measurer, t.text,
                                    topLeft = Offset(left, top),
                                    style = TextStyle(color = parseColor(t.color, Color.White), fontSize = (t.size * scale).sp),
                                )
                            }
                        }
                        "button" -> {
                            val label = d.button?.label ?: ""
                            val enabled = d.button?.enabled ?: true
                            drawRect(
                                if (enabled) Color(0xFFFF5722) else Color(0xFF616161),
                                Offset(left, top), Size(w, h), alpha = d.opacity,
                            )
                            if (label.isNotEmpty()) {
                                val style = TextStyle(color = Color.White, fontSize = (20f * scale).sp)
                                val layout = measurer.measure(label, style)
                                drawText(
                                    measurer, label,
                                    topLeft = Offset(
                                        left + (w - layout.size.width) / 2f,
                                        top + (h - layout.size.height) / 2f,
                                    ),
                                    style = style,
                                )
                            }
                        }
                        "sprite" -> {
                            val img = d.sprite?.assetId?.let { images[it] }
                            if (img != null) {
                                drawImage(
                                    img,
                                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                                    dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                                    alpha = d.opacity,
                                )
                            } else {
                                drawRect(
                                    d.sprite?.tint?.let { parseColor(it, Color(0xFF00BCD4)) } ?: Color(0xFF00BCD4),
                                    Offset(left, top), Size(w, h), alpha = d.opacity,
                                )
                                val tag = d.sprite?.assetId?.takeLast(8) ?: "IMG"
                                drawText(
                                    measurer, tag,
                                    topLeft = Offset(left + 6f * scale, top + 4f * scale),
                                    style = TextStyle(color = Color.Black, fontSize = (12f * scale).sp),
                                )
                            }
                        }
                        else -> drawRect(kindColor(d), Offset(left, top), Size(w, h), alpha = d.opacity)
                    }
                }
            }
        }
    }
}

private fun kindColor(d: Drawable): Color = when (d.kind) {
    "rect" -> Color(0xFF4CAF50)
    "circle" -> Color(0xFF2196F3)
    "panel" -> Color(0xFF9C27B0)
    else -> Color.Gray
}

private fun parseColor(hex: String, fallback: Color = Color(0xFF121212)): Color {
    return try {
        when (val clean = hex.trim().removePrefix("#")) {
            else -> if (clean.length == 6) Color(("FF$clean").toULong(16))
            else if (clean.length == 8) Color(clean.toULong(16))
            else fallback
        }
    } catch (_: Exception) {
        fallback
    }
}
