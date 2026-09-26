package compress.joshattic.us

import compress.joshattic.us.quality.CertificationDecision

/**
 * What a certification that did not pass does to the item and to the learned profile. One place,
 * so the ViewModel and the tests apply the same rule.
 *
 *  - Measured evidence (a window with enough frames below the bar, or frames out of time):
 *    SKIPPED_WOULD_DEGRADE, and the profile learns a quality failure at the ratio used.
 *  - Everything else (too few frames, nothing scored): the original is kept as UNEXPECTED_REMUX
 *    ("re-encode could not be verified"), and the learned state is left unchanged.
 *
 * Neither path accepts the output: acceptance is decided before this, by the unchanged pass rule.
 */
object CertificationFailure {
    fun terminalFor(decision: CertificationDecision): BatchTerminalResult =
        if (decision.isMeasuredNegative) BatchTerminalResult.SKIPPED_WOULD_DEGRADE else BatchTerminalResult.UNEXPECTED_REMUX

    fun teachesProfile(decision: CertificationDecision): Boolean = decision.isMeasuredNegative

    /**
     * Apply the rule to [engine] for one failed attempt. Returns the learned profile after the
     * update, or null when nothing was learned.
     */
    fun learn(
        engine: SmartPerceptualProfileEngine,
        decision: CertificationDecision,
        key: SmartPerceptualProfileEngine.EncodeProfileKey,
        usedRatio: Double,
        reason: String,
        floorRatio: Double,
        measuredOvershoot: Double?
    ): SmartPerceptualProfileEngine.LearnedEncodeProfile? =
        if (teachesProfile(decision)) engine.recordFailure(key, usedRatio, reason, floorRatio, measuredOvershoot) else null
}
