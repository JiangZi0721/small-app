package com.shakeguard.ui.rules

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shakeguard.data.ManagedPairRule
import com.shakeguard.protection.RuleKind
import org.junit.Rule
import org.junit.Test

class RulesScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @Test fun conflictRuleShowsConflictLabelAndDeleteConfirmation() {
        composeRule.setContent { RulesScreen(RulesUiState(rules = listOf(RuleListItem(ManagedPairRule(1, "news", "store", RuleKind.ALLOW, true, "UI", 1), true))), {}, { _, _ -> }, {}, {}) }
        composeRule.onNodeWithText("存在允许/阻止冲突").assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertIsDisplayed()
    }

    @Test fun editorSynchronizesWhenRuleFlowChangesFromEmptyToExisting() {
        var currentRule by mutableStateOf<ManagedPairRule?>(null)
        composeRule.setContent {
            RuleEditorScreen(currentRule, {}, {})
        }
        val existing = ManagedPairRule(7L, "news", "store", RuleKind.BLOCK, true, "UI", 1L)
        composeRule.runOnUiThread { currentRule = existing }
        composeRule.onNodeWithText("news").assertIsDisplayed()
        composeRule.onNodeWithText("store").assertIsDisplayed()
    }

    @Test fun editorKeepsLongPackageNamesVisibleInWideEditableFields() {
        val source = "com.baidu.netdisk.enterprise.client"
        val target = "com.sankuai.meituan.shopping.platform"
        composeRule.setContent {
            RuleEditorScreen(
                ManagedPairRule(8L, source, target, RuleKind.BLOCK, true, "UI", 1L),
                {},
                {},
            )
        }

        composeRule.onNode(hasText(source) and hasSetTextAction())
            .assertIsDisplayed()
            .assertWidthIsAtLeast(300.dp)
        composeRule.onNode(hasText(target) and hasSetTextAction())
            .assertIsDisplayed()
            .assertWidthIsAtLeast(300.dp)
    }
}
