package com.shakeguard.protection

class RuleEvaluator(
    private val excludedPackages: Set<String> = emptySet(),
) {
    fun evaluate(
        transition: ForegroundTransition,
        source: ProtectedSource?,
        pairRules: List<PairRule>,
    ): Decision {
        if (transition.isSamePackage || transition.targetPackage in excludedPackages) {
            return Decision(DecisionKind.IGNORE, "excluded transition")
        }
        if (
            source == null ||
            source.packageName != transition.sourcePackage ||
            !source.enabled ||
            transition.elapsedMs > source.windowMs
        ) {
            return Decision(DecisionKind.ALLOW, "outside protection window")
        }

        val matching = pairRules.filter {
            it.enabled && it.sourcePackage == transition.sourcePackage && it.targetPackage == transition.targetPackage
        }
        matching.firstOrNull { it.kind == RuleKind.ALLOW }?.let {
            return Decision(DecisionKind.ALLOW, "user allow rule", it.id)
        }
        if (source.sourceLevelBlock) {
            return Decision(DecisionKind.BLOCK, "source rule")
        }
        matching.firstOrNull { it.kind == RuleKind.BLOCK }?.let {
            return Decision(DecisionKind.BLOCK, "source-target rule", it.id)
        }
        return Decision(DecisionKind.OBSERVE, "no matching rule")
    }
}
