package com.baziche.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baziche.editor.EditorViewModel
import com.baziche.editor.R
import com.baziche.editor.core.ObjItem

private val TEXT_COLORS = listOf("#FFFFFF", "#000000", "#FF5252", "#4CAF50", "#2196F3", "#FFC107")

@Composable
fun InspectorPanel(obj: ObjItem, vm: EditorViewModel) {
    val t = obj.transform
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${obj.kind} · ${obj.id.takeLast(8)}")
                OutlinedButton(onClick = { vm.deleteSelected() }) { Text(stringResource(R.string.delete)) }
            }
            InspectorSlider("x", t.x, 0f, 800f) { vm.setTransform(t.copy(x = it)) }
            InspectorSlider("y", t.y, 0f, 800f) { vm.setTransform(t.copy(y = it)) }
            InspectorSlider(stringResource(R.string.width), t.w, 8f, 800f) { vm.setTransform(t.copy(w = it)) }
            InspectorSlider(stringResource(R.string.height), t.h, 8f, 800f) { vm.setTransform(t.copy(h = it)) }
            InspectorSlider(stringResource(R.string.rotation), t.rotation, 0f, 360f) { vm.setTransform(t.copy(rotation = it)) }
            InspectorSlider(stringResource(R.string.opacity), t.opacity, 0f, 1f) { vm.setTransform(t.copy(opacity = it)) }
            if (obj.kind == "text") {
                val cur = vm.selectedText()
                if (cur != null) {
                    OutlinedTextField(
                        value = cur.text,
                        onValueChange = { vm.setText(it, cur.size, cur.color) },
                        label = { Text(stringResource(R.string.text_content)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    InspectorSlider(stringResource(R.string.text_size), cur.size, 8f, 96f) { vm.setText(cur.text, it, cur.color) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TEXT_COLORS.forEach { hex ->
                            val selected = hex.equals(cur.color, ignoreCase = true)
                            Box(
                                Modifier
                                    .size(if (selected) 32.dp else 26.dp)
                                    .background(parseHex(hex))
                                    .clickable { vm.setText(cur.text, cur.size, hex) },
                            )
                        }
                    }
                }
            }
            if (obj.kind == "button") {
                val cur = vm.selectedButton()
                if (cur != null) {
                    OutlinedTextField(
                        value = cur.label,
                        onValueChange = { vm.setButtonLabel(it) },
                        label = { Text(stringResource(R.string.button_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.enabled))
                        Switch(checked = cur.enabled, onCheckedChange = { vm.setButtonEnabled(it) })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.layer, obj.layer))
                    OutlinedButton(onClick = { vm.changeLayer(-1) }) { Text("−") }
                    OutlinedButton(onClick = { vm.changeLayer(1) }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.visible))
                    Switch(checked = obj.visible, onCheckedChange = { vm.toggleVisible() })
                }
            }
        }
    }
}

private fun parseHex(hex: String): Color {
    return try {
        Color(("FF" + hex.trim().removePrefix("#")).toULong(16))
    } catch (_: Exception) {
        Color.White
    }
}

@Composable
private fun InspectorSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(0.35f))
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(0.5f))
        Text(value.toInt().toString(), Modifier.weight(0.15f))
    }
}
