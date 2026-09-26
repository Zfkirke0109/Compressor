package compress.joshattic.us

/**
 * The final word on a job's output, kept apart from the structural verification that preceded it.
 *
 * [OutputVerificationReport] answers "is this file structurally a faithful copy" (codec, color,
 * audio, timing, metadata, bitrate floor) and runs BEFORE pixel certification. Its verdict is
 * therefore not the item's outcome. b169 recorded `job_478c2fa19100` and `job_c92a4ca7e1be` with
 * `terminal=SKIPPED_WOULD_DEGRADE`, `pixelCertified=false`, `outputSize=0`, and also
 * `verdict=Perceptually Lossless Verified`, `verified=true`, `replacementSafe=true`: certification
 * had deleted the candidate, and the rejection path passed the earlier structural report to the
 * record unchanged. The UI had cleared the output, so nothing was replaced, but a reader of the
 * capture (or a summary counting "no failed checks") saw an accepted, replaceable output.
 *
 * Rules, in order:
 *  - a retained original (REUSED_SOURCE) is not an output: it is never replaceable and never
 *    "accepted", whatever its readability check said;
 *  - no structural report: nothing to accept;
 *  - a candidate that was discarded (no bytes kept, or a failure/skip/cancel terminal) is
 *    REJECTED: its verdict says so, and it is neither verified nor replaceable. Its structural
 *    verdict and size stay in their own fields as evidence;
 *  - otherwise the structural verdict stands, and replacement additionally needs a terminal that
 *    allows it.
 */
data class FinalAcceptance(
    /** The job kept an output file that passed every check it was subject to. */
    val accepted: Boolean,
    /** The job's final verdict (the record's `verdict`). */
    val verdict: String?,
    /** The record's `verified`: true only for an accepted output (or, legacy, a readable retained source). */
    val verified: Boolean,
    val replacementSafe: Boolean,
    /** Bytes of the output that was kept; 0 when none was. */
    val acceptedOutputBytes: Long,
    /** Size of the candidate the encoder or remux produced, whether kept or discarded; null when none existed. */
    val candidateBytes: Long?,
    /** The structural verifier's verdict on the candidate, unchanged. Null when it never ran. */
    val structuralVerdict: String?,
    val structuralVerified: Boolean?,
    val structuralReplacementSafe: Boolean?
) {
    companion object {
        /** Terminals that end a job without keeping any generated output, whatever the verifier said. */
        private val DISCARDING = setOf(
            BatchTerminalResult.SKIPPED_WOULD_DEGRADE,
            BatchTerminalResult.CANCELLED,
            BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED
        )

        fun rejectedVerdict(terminal: BatchTerminalResult): String = "Rejected — ${terminal.label}"

        fun of(
            terminal: BatchTerminalResult,
            structural: OutputVerificationReport?,
            keptOutputBytes: Long,
            candidateBytes: Long?,
            retainedVerdict: String? = null,
            retainedReadable: Boolean? = null
        ): FinalAcceptance {
            if (retainedVerdict != null || retainedReadable != null) {
                return FinalAcceptance(
                    accepted = false,
                    verdict = retainedVerdict,
                    verified = retainedReadable == true,
                    replacementSafe = false,
                    acceptedOutputBytes = 0L,
                    candidateBytes = candidateBytes,
                    structuralVerdict = null,
                    structuralVerified = null,
                    structuralReplacementSafe = null
                )
            }
            if (structural == null) {
                return FinalAcceptance(
                    accepted = false, verdict = null, verified = false, replacementSafe = false,
                    acceptedOutputBytes = 0L, candidateBytes = candidateBytes,
                    structuralVerdict = null, structuralVerified = null, structuralReplacementSafe = null
                )
            }
            val discarded = keptOutputBytes <= 0L || terminal.isFailure || terminal in DISCARDING
            return if (discarded) {
                FinalAcceptance(
                    accepted = false,
                    verdict = rejectedVerdict(terminal),
                    verified = false,
                    replacementSafe = false,
                    acceptedOutputBytes = 0L,
                    candidateBytes = candidateBytes ?: keptOutputBytes.takeIf { it > 0L },
                    structuralVerdict = structural.verdict,
                    structuralVerified = structural.verified,
                    structuralReplacementSafe = structural.replacementSafe
                )
            } else {
                FinalAcceptance(
                    accepted = structural.verified,
                    verdict = structural.verdict,
                    verified = structural.verified,
                    replacementSafe = structural.replacementSafe && terminal.allowsOriginalReplacement,
                    acceptedOutputBytes = keptOutputBytes,
                    candidateBytes = candidateBytes ?: keptOutputBytes,
                    structuralVerdict = structural.verdict,
                    structuralVerified = structural.verified,
                    structuralReplacementSafe = structural.replacementSafe
                )
            }
        }
    }
}
