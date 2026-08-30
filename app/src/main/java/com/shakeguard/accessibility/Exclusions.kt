package com.shakeguard.accessibility

import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager

object Exclusions {
    fun discover(context: Context): Set<String> {
        val packageManager = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackages = packageManager.queryIntentActivities(homeIntent, 0)
            .map { it.activityInfo.packageName }
        val inputMethodManager = context.getSystemService(InputMethodManager::class.java)
        val inputMethodPackages = inputMethodManager.inputMethodList.map { it.packageName }
        return buildSet {
            add(context.packageName)
            add("android")
            add("com.android.systemui")
            add("com.android.permissioncontroller")
            add("com.google.android.permissioncontroller")
            addAll(homePackages)
            addAll(inputMethodPackages)
        }
    }
}
