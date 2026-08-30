package com.shakeguard.ui.navigation

object AppRoutes {
    const val HOME = "home"
    const val RULES = "rules"
    const val ACTIVITY = "activity"
    const val APP_PICKER = "app-picker"
    const val SOURCE = "source/{packageName}"
    const val RULE_EDITOR = "rule-editor?ruleId={ruleId}"

    fun source(packageName: String): String = "source/$packageName"

    fun ruleEditor(ruleId: Long?): String =
        if (ruleId == null) "rule-editor" else "rule-editor?ruleId=$ruleId"
}
