package com.baziche.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baziche.editor.EditorViewModel
import com.baziche.editor.R
import com.baziche.editor.core.ObjItem

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

@Composable
private fun InspectorSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(0.35f))
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max, modifier = Modifier.weight(0.5f))
        Text(value.toInt().toString(), Modifier.weight(0.15f))
    }
}
