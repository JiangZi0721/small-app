package com.shakeguard.ui.sources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

@Composable
fun SourceDetailScreen(
    state: SourceDetailUiState,
    onEnabledChanged: (Boolean) -> Unit,
    onWindowSecondsChanged: (String) -> Unit,
    onSourceLevelBlockChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onShowRelatedRules: () -> Unit,
) {
    Column {
        state.icon?.let { icon ->
            runCatching { icon.toBitmap().asImageBitmap() }.getOrNull()?.let { bitmap ->
                Image(bitmap = bitmap, contentDescription = state.label)
            }
        }
        Text(state.label)
        Text(state.packageName)
        Text(if (state.enabled) "保护中" else "已暂停")
        Switch(checked = state.enabled, onCheckedChange = onEnabledChanged)
        if (!state.enabled) Button(onClick = { onEnabledChanged(true) }) { Text("恢复保护") }
        val seconds = (state.windowMs / 1_000L).coerceIn(1L, 30L)
        Text("时间窗：${seconds} 秒")
        Slider(value = seconds.toFloat(), onValueChange = { onWindowSecondsChanged(it.toInt().toString()) }, valueRange = 1f..30f)
        TextField(value = seconds.toString(), onValueChange = onWindowSecondsChanged, label = { Text("时间窗秒数") })
        Text("来源级阻断")
        Switch(checked = state.sourceLevelBlock, onCheckedChange = onSourceLevelBlockChanged)
        Text("相关规则：${state.relatedRuleCount}")
        Button(onClick = onShowRelatedRules) { Text("查看相关规则") }
        state.errorMessage?.let { Text(it) }
        Button(onClick = onSave, enabled = !state.saving) { Text(if (state.saving) "保存中" else "保存") }
    }
}
