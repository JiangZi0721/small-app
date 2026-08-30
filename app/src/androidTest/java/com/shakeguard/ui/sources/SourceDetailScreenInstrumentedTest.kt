package com.shakeguard.ui.sources

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class SourceDetailScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pausedSourceShowsRestoreProtectionAndRelatedRules() {
        composeRule.setContent {
            SourceDetailScreen(
                state = SourceDetailUiState(
                    packageName = "news.app",
                    label = "Daily News",
                    icon = null,
                    enabled = false,
                    windowMs = 5_000L,
                    sourceLevelBlock = true,
                    relatedRuleCount = 2,
                ),
                onEnabledChanged = {},
                onWindowSecondsChanged = {},
                onSourceLevelBlockChanged = {},
                onSave = {},
                onShowRelatedRules = {},
            )
        }

        composeRule.onNodeWithText("已暂停").assertIsDisplayed()
        composeRule.onNodeWithText("恢复保护").assertIsDisplayed()
        composeRule.onNodeWithText("查看相关规则").assertIsDisplayed()
    }
}
