package com.baziche.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.baziche.editor.EditorViewModel
import com.baziche.editor.R
import com.baziche.editor.core.ObjItem
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures

private val KINDS = listOf("rect", "circle", "text", "button", "panel", "sprite")

private fun kindColor(kind: String): Color = when (kind) {
    "rect" -> Color(0xFF4CAF50)
    "circle" -> Color(0xFF2196F3)
    "text" -> Color(0xFFFFC107)
    "button" -> Color(0xFFFF5722)
    "panel" -> Color(0xFF9C27B0)
    "sprite" -> Color(0xFF00BCD4)
    else -> Color.Gray
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditorScreen(vm: EditorViewModel, onBack: () -> Unit, onPreview: () -> Unit) {
    val ui by vm.ui.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.projectName.ifBlank { stringResource(R.string.editor) } + " · r${ui.rev}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
                actions = {
                    if (ui.dirty) Text("•", color = MaterialTheme.colorScheme.tertiary)
                    TextButton(onClick = onPreview, enabled = !ui.loading) {
                        Text(stringResource(R.string.preview))
                    }
                    TextButton(onClick = { vm.save() }, enabled = !ui.saving && !ui.loading) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { pad ->
        when {
            ui.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null && !ui.dirty -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (ui.error == "offline_saved") stringResource(R.string.offline_saved) else ui.error!!)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.load() }) { Text(stringResource(R.string.retry)) }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(pad)) {
                SceneStrip(vm)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EditorCanvas(vm)
                    if (ui.saving) CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                PaletteRow(vm)
                ui.selected?.let { InspectorPanel(it, vm) }
                ui.error?.let {
                    Card(
                        Modifier.fillMaxWidth().padding(8.dp).clickable { vm.dismissError() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            if (it == "offline_saved") stringResource(R.string.offline_saved) else it,
                            Modifier.padding(12.dp),
                        )
                    }
                }
                ui.mergeNote?.let { conflicts ->
                    Card(
                        Modifier.fillMaxWidth().padding(8.dp).clickable { vm.dismissMergeNote() },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    ) {
                        Text(
                            if (conflicts.isEmpty()) stringResource(R.string.merged_clean)
                            else stringResource(R.string.merged_conflicts, conflicts.joinToString(", ")),
                            Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
        if (ui.addingScene) {
            TextInputDialog(
                title = stringResource(R.string.new_scene),
                initial = "",
                onDismiss = { vm.cancelAddScene() },
                onConfirm = { vm.addScene(it) },
            )
        }
        ui.renamingScene?.let { id ->
            val scene = ui.scenes.firstOrNull { it.id == id }
            TextInputDialog(
                title = stringResource(R.string.rename_scene),
                initial = scene?.name ?: "",
                onDismiss = { vm.cancelRenameScene() },
                onConfirm = { vm.renameScene(id, it) },
            )
        }
        ui.conflict?.let { c ->
            ConflictDialog(
                serverRev = c.serverRev,
                onMerge = { vm.autoMerge() },
                onKeepMine = { vm.forceSave() },
                onUseServer = { vm.useServer() },
            )
        }
    }
}

@Composable
private fun SceneStrip(vm: EditorViewModel) {
    val ui by vm.ui.collectAsState()
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ui.scenes) { scene ->
            Box {
                FilterChip(
                    selected = scene.id == ui.activeSceneId,
                    onClick = { vm.selectScene(scene.id) },
                    label = { Text((if (scene.entry) "★ " else "") + scene.name) },
                )
                // Long-press menu anchor: a transparent overlay is complex; instead the
                // selected scene shows its menu via the dedicated button below.
            }
            if (ui.pendingSceneMenu == scene.id) {
                DropdownMenu(expanded = true, onDismissRequest = { vm.closeSceneMenu() }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { vm.promptRenameScene(scene.id) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = { vm.duplicateScene(scene.id) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.set_entry)) }, onClick = { vm.setEntry(scene.id) })
                    DropdownMenuItem(text = { Text("←") }, onClick = { vm.moveScene(scene.id, -1) })
                    DropdownMenuItem(text = { Text("→") }, onClick = { vm.moveScene(scene.id, 1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { vm.deleteScene(scene.id) })
                }
            }
        }
        item {
            OutlinedButton(onClick = { vm.promptAddScene() }) { Text("+") }
        }
        ui.activeScene?.let { active ->
            item {
                OutlinedButton(onClick = { vm.openSceneMenu(active.id) }) { Text("⋮") }
            }
        }
    }
}

@Composable
private fun PaletteRow(vm: EditorViewModel) {
    val ui by vm.ui.collectAsState()
    val logical = logicalSize(ui.orientation)
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(KINDS) { kind ->
            OutlinedButton(onClick = { vm.addObject(kind, logical.width / 2 - 60, logical.height / 2 - 60) }) {
                Text(kind)
            }
        }
    }
}

private fun logicalSize(orientation: String): Size =
    if (orientation == "landscape") Size(800f, 480f) else Size(480f, 800f)

@Composable
private fun EditorCanvas(vm: EditorViewModel) {
    val ui by vm.ui.collectAsState()
    val logical = remember(ui.orientation) { logicalSize(ui.orientation) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0B0B0F))) {
        val scale = remember(maxWidth, maxHeight, logical) {
            minOf(
                with(density) { maxWidth.toPx() } / logical.width,
                with(density) { maxHeight.toPx() } / logical.height,
            )
        }
        val objects = ui.objects
        val selectedId = ui.selectedId
        Canvas(
            Modifier
                .width(with(density) { (logical.width * scale).toDp() })
                .height(with(density) { (logical.height * scale).toDp() })
                .align(Alignment.Center)
                .pointerInput(objects, scale) {
                    detectTapGestures { tap ->
                        val p = Offset(tap.x / scale, tap.y / scale)
                        vm.select(hitTest(objects, p))
                    }
                }
                .pointerInput(objects, scale, selectedId) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val p = Offset(start.x / scale, start.y / scale)
                            hitTest(objects, p)?.let { vm.select(it) }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            vm.moveSelected(drag.x / scale, drag.y / scale)
                        },
                    )
                },
        ) {
            // Canvas backing + grid.
            drawRect(Color(0xFF121218), size = size)
            val step = 40f * scale
            var gx = 0f
            while (gx <= size.width) {
                drawLine(Color(0xFF23232B), Offset(gx, 0f), Offset(gx, size.height))
                gx += step
            }
            var gy = 0f
            while (gy <= size.height) {
                drawLine(Color(0xFF23232B), Offset(0f, gy), Offset(size.width, gy))
                gy += step
            }
            fun X(v: Float) = v * scale
            for (o in objects) {
                if (!o.visible) continue
                val t = o.transform
                val c = kindColor(o.kind).copy(alpha = t.opacity.coerceIn(0f, 1f))
                val topLeft = Offset(X(t.x), X(t.y))
                val sz = Size(X(t.w), X(t.h))
                when (o.kind) {
                    "circle" -> drawOval(c, topLeft, sz)
                    "text" -> {
                        drawRect(c.copy(alpha = 0.25f), topLeft, sz)
                        drawText(measurer, "T", topLeft = topLeft + Offset(8f * scale, 4f * scale))
                    }
                    else -> drawRect(c, topLeft, sz)
                }
                if (o.id == selectedId) {
                    drawRect(Color.White, topLeft, sz, style = Stroke(width = 2f * scale))
                }
            }
        }
    }
}

/** Topmost (highest layer) object containing [p], or null. Hidden objects are skipped. */
private fun hitTest(objects: List<ObjItem>, p: Offset): String? =
    objects
        .filter { it.visible }
        .sortedByDescending { it.layer }
        .firstOrNull { o ->
            val t = o.transform
            p.x in t.x..(t.x + t.w) && p.y in t.y..(t.y + t.h)
        }?.id
