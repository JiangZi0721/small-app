package com.shakeguard.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable fun RulesScreen(state: RulesUiState, onFilter: (RuleFilter) -> Unit, onEnabled: (Long, Boolean) -> Unit, onDelete: (Long) -> Unit, onEdit: (Long?) -> Unit) {
    var deleteId by remember { mutableStateOf<Long?>(null) }
    Column {
        TabRow(selectedTabIndex = state.filter.ordinal) { RuleFilter.entries.forEach { filter -> Tab(selected = state.filter == filter, onClick = { onFilter(filter) }, text = { Text(when(filter) { RuleFilter.All -> "全部"; RuleFilter.Allow -> "允许"; RuleFilter.Block -> "阻止"; RuleFilter.Conflict -> "冲突" }) }) } }
        Button(onClick = { onEdit(null) }) { Text("新增规则") }
        state.errorMessage?.let { Text(it) }
        LazyColumn { items(state.rules, key = { it.id }) { item -> Column(Modifier.clickable { onEdit(item.id) }) { Text("${item.rule.sourcePackage} → ${item.rule.targetPackage}"); Text(item.rule.kind.name); Text(item.rule.origin); Text(item.rule.updatedAt.toString()); if(item.hasConflict) Text("存在允许/阻止冲突"); Switch(item.rule.enabled, { onEnabled(item.id, it) }); Button(onClick = { deleteId = item.id }) { Text("删除") } } } }
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, confirmButton = { Button(onClick = { onDelete(id); deleteId = null }) { Text("确认删除") } }, dismissButton = { Button(onClick = { deleteId = null }) { Text("取消") } }, text = { Text("确认删除规则？") }) }
}
