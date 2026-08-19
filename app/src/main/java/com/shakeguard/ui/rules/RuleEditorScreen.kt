package com.shakeguard.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.shakeguard.data.ManagedPairRule
import com.shakeguard.protection.RuleKind

@Composable fun RuleEditorScreen(rule: ManagedPairRule?, onSave: (ManagedPairRule) -> Unit, onExit: () -> Unit) = key(rule?.id) {
    var source by remember { mutableStateOf(rule?.sourcePackage.orEmpty()) }; var target by remember { mutableStateOf(rule?.targetPackage.orEmpty()) }; var kind by remember { mutableStateOf(rule?.kind ?: RuleKind.ALLOW) }
    Column {
        TextField(
            value = source,
            onValueChange = { source = it },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("来源包名") },
        )
        TextField(
            value = target,
            onValueChange = { target = it },
            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("目标包名") },
        )
        Button({ kind = RuleKind.ALLOW }) { Text("允许") }
        Button({ kind = RuleKind.BLOCK }) { Text("阻止") }
        if (rule != null && rule.kind != kind) Text("存在允许/阻止冲突")
        Button(onClick = {
            if (source.isNotBlank() && target.isNotBlank()) {
                onSave(ManagedPairRule(rule?.id ?: 0L, source, target, kind, rule?.enabled ?: true, rule?.origin ?: "UI", rule?.updatedAt ?: 0L))
            }
        }) { Text("保存") }
        Button(onClick = onExit) { Text("退出") }
    }
}
