package com.shakeguard.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable fun ActivityLogScreen(state: ActivityLogUiState, onRequestClear: () -> Unit, onConfirmClear: () -> Unit, onDismissClear: () -> Unit, onFeedback: (com.shakeguard.feedback.FeedbackCommand) -> Unit) {
    Column {
        Button(onClick = onRequestClear) { Text("清空全部记录") }
        state.errorMessage?.let { Text(it) }
        if (state.isEmpty) Text("暂无活动记录；来源和规则仍会保留")
        LazyColumn { items(state.events, key = { it.id }) { event -> Column { Text("${event.sourcePackage} → ${event.targetPackage}"); Text(event.decision); Text(event.actionResult); event.feedbackActions.forEach { command -> Button(onClick = { onFeedback(command) }) { Text(command::class.simpleName ?: "反馈") } } } } }
    }
    if (state.clearConfirmationVisible) AlertDialog(onDismissRequest = onDismissClear, confirmButton = { Button(onClick = onConfirmClear) { Text("确认清空") } }, dismissButton = { Button(onClick = onDismissClear) { Text("取消") } }, text = { Text("清空全部活动记录？来源和规则不会删除") })
}
