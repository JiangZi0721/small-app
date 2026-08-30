package com.shakeguard.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.shakeguard.ui.activity.ActivityLogScreen
import com.shakeguard.ui.activity.ActivityLogUiState
import com.shakeguard.ui.home.HomeScreen
import com.shakeguard.ui.home.HomeState
import com.shakeguard.ui.home.HomeStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OperablePrototypeUiInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun activityLogShowsEmptyStateAndClearEntry() {
        var clearRequested = false
        composeRule.setContent {
            ActivityLogScreen(
                state = ActivityLogUiState(isEmpty = true, loading = false),
                onRequestClear = { clearRequested = true },
                onConfirmClear = {}, onDismissClear = {}, onFeedback = {},
            )
        }
        composeRule.onNodeWithText("暂无活动记录；来源和规则仍会保留").assertIsDisplayed()
        composeRule.onNodeWithText("清空全部记录").performClick()
        assertTrue(clearRequested)
    }

    @Test fun homeShowsStableProtectionDisabledStatus() {
        composeRule.setContent {
            HomeScreen(
                state = HomeState(status = HomeStatus.ProtectionDisabled, serviceConnected = true),
                onProtectionEnabledChange = {}, onAddSource = {},
                onOpenAccessibilitySettings = {}, onOpenNotificationSettings = {},
            )
        }
        composeRule.onNodeWithText("保护已暂停").assertIsDisplayed()
    }
}
