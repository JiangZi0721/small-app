package com.shakeguard.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun HomeScreen(
    state: HomeState,
    onProtectionEnabledChange: (Boolean) -> Unit,
    onAddSource: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Column {
        Text("首页页面")
        when (state.status) {
            HomeStatus.Loading -> Text("正在检查保护状态")
            HomeStatus.AccessibilityDisabled -> {
                Text("无障碍服务未启用")
                Button(onClick = onOpenAccessibilitySettings) { Text("打开无障碍设置") }
            }
            HomeStatus.ProtectionDisabled -> {
                Text("保护已暂停")
                Text("无障碍服务已连接")
                Button(onClick = { onProtectionEnabledChange(true) }) { Text("恢复保护") }
            }
            HomeStatus.NoSources -> {
                Text("尚未添加来源应用")
            }
            HomeStatus.Active -> {
                Text("保护运行中")
                Button(onClick = { onProtectionEnabledChange(false) }) { Text("暂停保护") }
            }
            HomeStatus.Error -> Text("状态读取失败")
        }
        Button(onClick = onAddSource) { Text("管理受保护来源") }
        if (!state.notificationsAvailable) {
            Text("通知提醒不可用")
            Button(onClick = onOpenNotificationSettings) { Text("打开通知设置") }
        }
    }
}
