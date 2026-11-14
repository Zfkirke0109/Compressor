package compress.joshattic.us

/**
 * A hook that may refuse a structurally verified Perceptually Lossless candidate before its pixel
 * certification. The app never refuses: its view model is built with [NONE], no setting, intent
 * or build flag selects anything else, CandidateVetoTest fails the build if the main source set
 * gains another implementation or another way to construct the view model, and CI reads the
 * packaged app's DEX for a second implementation (scripts/ci/verify_no_test_veto.py).
 *
 * It exists for one instrumented test (SaferRungFallbackDeviceTest). A marginal trial whose first
 * full encode is rejected falls back to the safer ratio its ladder measured as passing. The
 * Secure Folder run that selected a real 0.75 trial certified it at the first attempt, so the
 * fallback encode had never run on a device. The test builds its own view model with a veto that
 * refuses attempt 1; everything after that is the production path. The candidate is discarded,
 * SaferRungRetry decides the retry, and the second encode is verified, certified and accepted or
 * kept as the original by the unchanged rules.
 *
 * What a veto can do is fixed here, not by whoever writes one:
 *  - it can only take acceptance away ([stands]): no return value makes a candidate pass;
 *  - a refused attempt was not measured. It is recorded as [WIRE], never as a measured failure
 *    or as an unavailable certification, and nothing is written to the learned profiles for it;
 *  - a batch run with a veto says so in its `run_identity` record, so its capture cannot be read
 *    as field evidence.
 */
internal fun interface CandidateVeto {

    /** A reason to refuse this attempt's candidate, or null to let certification decide. */
    fun reject(attemptIndex: Int, targetRatio: Double): String?

    companion object {
        /** Refuses nothing. The only instance the app itself constructs. */
        val NONE = CandidateVeto { _, _ -> null }

        /** How a refused attempt is named: its ledger outcome, the retry's trigger, its record. */
        const val WIRE = "test_rejected"

        /** Whether the certification gate's acceptance stands. A veto can only take it away. */
        fun stands(gateAccepted: Boolean, vetoReason: String?): Boolean = gateAccepted && vetoReason == null
    }
}
