package com.shakeguard.protection

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEvaluatorTest {
    private val evaluator = RuleEvaluator(setOf("android", "com.shakeguard"))
    private val source = ProtectedSource("news", windowMs = 5_000L)

    @Test fun samePackageIsIgnored() = assertEquals(DecisionKind.IGNORE, evaluator.evaluate(ForegroundTransition("news", "news", 50, true), source, emptyList()).kind)
    @Test fun missingSourceConfigurationIsAllowed() = assertEquals(DecisionKind.ALLOW, evaluator.evaluate(ForegroundTransition("news", "store", 50), null, emptyList()).kind)
    @Test fun disabledSourceConfigurationIsAllowed() = assertEquals(DecisionKind.ALLOW, evaluator.evaluate(ForegroundTransition("news", "store", 50), source.copy(enabled = false), emptyList()).kind)
    @Test fun outsideWindowIsAllowed() = assertEquals(DecisionKind.ALLOW, evaluator.evaluate(ForegroundTransition("news", "store", 5_001), source, emptyList()).kind)
    @Test fun pairBlockIsInactiveOutsideWindow() = assertEquals(DecisionKind.ALLOW, evaluator.evaluate(ForegroundTransition("news", "store", 5_001), source.copy(sourceLevelBlock = false), listOf(PairRule(4, "news", "store", RuleKind.BLOCK))).kind)
    @Test fun exactWindowBoundaryStillAppliesRules() = assertEquals(DecisionKind.BLOCK, evaluator.evaluate(ForegroundTransition("news", "store", 5_000), source, emptyList()).kind)
    @Test fun mismatchedSourceConfigurationDoesNotApplyRules() = assertEquals(DecisionKind.ALLOW, evaluator.evaluate(ForegroundTransition("video", "store", 100), source, emptyList()).kind)
    @Test fun allowRuleOverridesSourceBlock() = assertEquals(DecisionKind.ALLOW, evaluator.evaluate(ForegroundTransition("news", "login", 100), source, listOf(PairRule(1, "news", "login", RuleKind.ALLOW))).kind)
    @Test fun sourceRuleBlocksWithinWindow() = assertEquals(DecisionKind.BLOCK, evaluator.evaluate(ForegroundTransition("news", "store", 100), source, emptyList()).kind)
    @Test fun pairBlockRuleAppliesWhenSourceRuleDisabled() = assertEquals(DecisionKind.BLOCK, evaluator.evaluate(ForegroundTransition("news", "browser", 100), source.copy(sourceLevelBlock = false), listOf(PairRule(2, "news", "browser", RuleKind.BLOCK))).kind)
    @Test fun allowPairWinsWhenConflictingPairRulesExist() = assertEquals(DecisionKind.ALLOW, evaluator.evaluate(ForegroundTransition("news", "browser", 100), source.copy(sourceLevelBlock = false), listOf(PairRule(5, "news", "browser", RuleKind.BLOCK), PairRule(6, "news", "browser", RuleKind.ALLOW))).kind)
    @Test fun disabledAllowRuleDoesNotOverrideSourceBlock() = assertEquals(DecisionKind.BLOCK, evaluator.evaluate(ForegroundTransition("news", "login", 100), source, listOf(PairRule(3, "news", "login", RuleKind.ALLOW, enabled = false))).kind)
    @Test fun excludedPackageIsIgnored() = assertEquals(DecisionKind.IGNORE, evaluator.evaluate(ForegroundTransition("news", "android", 100), source, emptyList()).kind)
    @Test fun unmatchedPairIsObservedWhenSourceRuleDisabled() = assertEquals(DecisionKind.OBSERVE, evaluator.evaluate(ForegroundTransition("news", "browser", 100), source.copy(sourceLevelBlock = false), emptyList()).kind)
}
