package com.shakeguard.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.Modifier

@Composable
fun SourcePickerScreen(state: SourcePickerUiState, onQueryChanged: (String) -> Unit, onSelectPackage: (String) -> Unit) {
    Column {
        TextField(value = state.query, onValueChange = onQueryChanged, label = { Text("搜索应用") })
        if (state.loading) Text("正在加载应用")
        LazyColumn {
            items(state.visibleApps, key = { it.packageName }) { item ->
                Column(Modifier.fillMaxWidth().clickable { onSelectPackage(item.packageName) }) {
                    item.icon?.let { icon ->
                        runCatching { icon.toBitmap().asImageBitmap() }.getOrNull()?.let { bitmap ->
                            Image(bitmap = bitmap, contentDescription = item.label)
                        }
                    }
                    Text(item.label)
                    Text(item.packageName)
                    Text(when (item.status) { SourcePickerStatus.Unconfigured -> "未配置"; SourcePickerStatus.Protected -> "已保护"; SourcePickerStatus.Paused -> "已暂停" })
                }
            }
        }
    }
}
