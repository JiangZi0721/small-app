package com.shakeguard.ui.activity

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class ActivityLogScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @Test fun clearRequiresConfirmationAndEmptyStateExplainsRulesAreKept() {
        var requested = false
        composeRule.setContent {
            ActivityLogScreen(ActivityLogUiState(isEmpty = true, loading = false), { requested = true }, {}, {}, {})
        }
        composeRule.onNodeWithText("暂无活动记录").assertIsDisplayed()
        composeRule.onNodeWithText("清空全部记录").performClick()
        assert(requested)
    }
}
