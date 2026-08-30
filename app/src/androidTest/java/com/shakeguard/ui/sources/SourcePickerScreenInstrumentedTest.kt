package com.shakeguard.ui.sources

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SourcePickerScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pausedSourceIsVisibleAndClickEmitsOnlyPackageName() {
        var selected: String? = null
        composeRule.setContent {
            SourcePickerScreen(
                state = SourcePickerUiState(
                    visibleApps = listOf(SourcePickerItem("news.app", "Daily News", null, SourcePickerStatus.Paused)),
                ),
                onQueryChanged = {},
                onSelectPackage = { selected = it },
            )
        }

        composeRule.onNodeWithText("Daily News").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("已暂停").assertIsDisplayed()
        assertEquals("news.app", selected)
    }

    @Test
    fun nonEmptyIconIsRenderedWithStableContentDescription() {
        composeRule.setContent {
            SourcePickerScreen(
                state = SourcePickerUiState(
                    visibleApps = listOf(SourcePickerItem("news.app", "Daily News", android.graphics.drawable.ColorDrawable(android.graphics.Color.RED), SourcePickerStatus.Unconfigured)),
                ),
                onQueryChanged = {},
                onSelectPackage = {},
            )
        }

        composeRule.onNodeWithContentDescription("Daily News").assertIsDisplayed()
    }
}
