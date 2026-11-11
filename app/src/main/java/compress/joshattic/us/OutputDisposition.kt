package compress.joshattic.us

/**
 * What became of a job's output, as separate states a capture reader does not have to infer.
 *
 * One job can be all of "an output was generated", "it verified", "it is kept and offered as a
 * copy" and "it does not count as compression". b287 Secure Folder High Quality job_31e5faeaf665
 * was exactly that: a 122,130,776 B encode of a 123,783,185 B source. Its record read
 * `finalAccepted=true`, its accept event `accepted`, its attempt ledger
 * `not_accepted:TRANSCODED_NOT_MEANINGFULLY_SMALLER`, and each was the true answer to a different
 * question. The same batch's Perceptually Lossless size-gate keep ended with an accept event
 * `rejected` although nothing had been generated to reject.
 *
 * Those strings stay as they are (captures are compared across builds by them). This value states
 * each question on its own:
 *  - [candidateGenerated]: an encode or stream copy produced a file, whatever happened next;
 *  - [structurallyVerified]: the structural verifier passed that file (null when it never ran);
 *  - [pixelCertified]: sampled pixel certification measured the kept output and passed;
 *  - [output]: what the job leaves the user ([OUTPUT_NONE], [ORIGINAL_RETAINED], [KEPT_FOR_COPY]);
 *  - [candidateRejected]: a generated candidate was discarded;
 *  - [qualifyingSavings]: the kept output counts toward the accepted size reduction;
 *  - [replacementEligible]: the kept output may replace its original, if the batch asks for that.
 *
 * Whether replacement was then requested, committed or rolled back is a later, separate event
 * (the `replace` stage): eligibility is a property of the output, not a write.
 */
data class OutputDisposition(
    val candidateGenerated: Boolean,
    val structurallyVerified: Boolean?,
    val pixelCertified: Boolean,
    val output: String,
    val candidateRejected: Boolean,
    val qualifyingSavings: Boolean,
    val replacementEligible: Boolean
) {
    fun fields(): Map<String, Any?> = linkedMapOf(
        "candidateGenerated" to candidateGenerated,
        "structurallyVerified" to structurallyVerified,
        "pixelCertified" to pixelCertified,
        "output" to output,
        "candidateRejected" to candidateRejected,
        "qualifyingSavings" to qualifyingSavings,
        "replacementEligible" to replacementEligible
    )

    companion object {
        const val OUTPUT_NONE = "none"
        const val ORIGINAL_RETAINED = "original_retained"
        const val KEPT_FOR_COPY = "kept_for_copy"

        /**
         * @param originalRetained the job surfaced the source itself with no copy written
         *   (materialization REUSED_SOURCE), as opposed to keeping a generated file.
         */
        fun of(
            terminal: BatchTerminalResult,
            acceptance: FinalAcceptance,
            originalRetained: Boolean,
            pixelCertified: Boolean
        ): OutputDisposition {
            val generated = (acceptance.candidateBytes ?: 0L) > 0L
            val output = when {
                // A retained source that could not be read ends as a failure: nothing is offered.
                originalRetained -> if (!terminal.isFailure && acceptance.verified) ORIGINAL_RETAINED else OUTPUT_NONE
                acceptance.acceptedOutputBytes > 0L -> KEPT_FOR_COPY
                else -> OUTPUT_NONE
            }
            val kept = output == KEPT_FOR_COPY
            return OutputDisposition(
                candidateGenerated = generated,
                structurallyVerified = acceptance.structuralVerified,
                // A certificate belongs to a kept output; a discarded candidate carries none.
                pixelCertified = kept && pixelCertified,
                output = output,
                candidateRejected = generated && !kept,
                qualifyingSavings = kept && terminal.countsAsRealCompression,
                replacementEligible = kept && acceptance.replacementSafe
            )
        }
    }
}
