package com.shakeguard.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.shakeguard.ui.navigation.AppRoutes
import org.junit.Rule
import org.junit.Test

class AppNavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bottomNavigationSwitchesBetweenHomeRulesAndActivity() {
        composeRule.setContent { ShakeGuardApp() }

        composeRule.onNodeWithText("首页页面").assertIsDisplayed()
        composeRule.onNodeWithText("规则").performClick()
        composeRule.onNodeWithText("规则页面").assertIsDisplayed()
        composeRule.onNodeWithText("活动").performClick()
        composeRule.onNodeWithText("活动页面").assertIsDisplayed()
        composeRule.onNodeWithText("首页").performClick()
        composeRule.onNodeWithText("首页页面").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationIsHiddenOnChildRoute() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            ShakeGuardApp(navController)
        }

        composeRule.runOnUiThread {
            navController.navigate(AppRoutes.APP_PICKER)
        }

        composeRule.onNodeWithText("搜索应用").assertIsDisplayed()
        composeRule.onAllNodesWithText("首页").assertCountEquals(0)
        composeRule.onAllNodesWithText("规则").assertCountEquals(0)
        composeRule.onAllNodesWithText("活动").assertCountEquals(0)
    }

    @Test
    fun homeSourceManagementOpensAppPicker() {
        composeRule.setContent { ShakeGuardApp() }

        composeRule.onNodeWithText("管理受保护来源").performClick()
        composeRule.onNodeWithText("搜索应用").assertIsDisplayed()
    }
}
