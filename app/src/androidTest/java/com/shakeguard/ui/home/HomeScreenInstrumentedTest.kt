package com.shakeguard.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shakeguard.ui.system.NotificationCapability
import org.junit.Rule
import org.junit.Test

class HomeScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun protectionDisabledKeepsServiceStatusAndShowsNotificationWarning() {
        composeRule.setContent {
            HomeScreen(
                state = HomeState(
                    status = HomeStatus.ProtectionDisabled,
                    serviceConnected = true,
                    notificationsAvailable = false,
                ),
                onProtectionEnabledChange = {},
                onAddSource = {},
                onOpenAccessibilitySettings = {},
                onOpenNotificationSettings = {},
            )
        }

        composeRule.onNodeWithText("保护已暂停").assertIsDisplayed()
        composeRule.onNodeWithText("无障碍服务已连接").assertIsDisplayed()
        composeRule.onNodeWithText("通知提醒不可用").assertIsDisplayed()
    }

    @Test
    fun activeHomeShowsSourceManagementEntry() {
        var opened = false
        composeRule.setContent {
            HomeScreen(
                state = HomeState(status = HomeStatus.Active, serviceConnected = true),
                onProtectionEnabledChange = {},
                onAddSource = { opened = true },
                onOpenAccessibilitySettings = {},
                onOpenNotificationSettings = {},
            )
        }

        composeRule.onNodeWithText("管理受保护来源").assertIsDisplayed().performClick()
        org.junit.Assert.assertTrue(opened)
    }
}
