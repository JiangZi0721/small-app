package com.shakeguard.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.shakeguard.ui.navigation.AppNavHost
import com.shakeguard.ui.navigation.AppRoutes

@Composable
fun ShakeGuardApp(
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val destinations = listOf(
        AppRoutes.HOME to "首页",
        AppRoutes.RULES to "规则",
        AppRoutes.ACTIVITY to "活动",
    )
    val isTopLevelRoute = currentRoute in destinations.map { it.first }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                if (isTopLevelRoute) {
                    NavigationBar {
                        destinations.forEach { (route, label) ->
                            NavigationBarItem(
                                selected = currentRoute == route,
                                onClick = {
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                        popUpTo(AppRoutes.HOME) { saveState = true }
                                        restoreState = true
                                    }
                                },
                                icon = { Text(label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { paddingValues ->
            AppNavHost(navController = navController, contentPadding = paddingValues)
        }
    }
}
