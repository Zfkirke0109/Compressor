package compress.joshattic.us

import android.net.Uri
import java.io.File

/**
 * Explicit, strongly typed description of HOW a batch item's result was materialized.
 *
 * Evidence basis (docs/pr23/REMUX_ACCELERATION_INVESTIGATION.md, captures batch_20260715_141019 /
 * _193710): keep-original remux decisions spent ~40 min per 172-file batch physically stream-
 * copying sources that the algorithm had already decided NOT to compress (r=0.80 between remux
 * time and file size; worst case 267 s to "save" 0 bytes). The copy is only ever needed when a
 * DISTINCT output must exist (explicit Remux Only, privacy stripping, container normalization,
 * failed-encode fallback). When the decision is simply "keep the original", the original itself
 * is the result.
 *
 * This model makes that distinction impossible to blur: every downstream consumer sees either a
 * [GeneratedFile] (a new artifact that was written and verified) or a [ReusedSource] (the
 * untouched original, never opened for write, with the audited reason it was safe to reuse).
 */
sealed interface MaterializedOutput {
    /** The untouched original is the result. No copy was written; nothing was verified as a copy. */
    data class ReusedSource(
        val sourceUri: Uri,
        val decision: OriginalReuseDecision.Eligible
    ) : MaterializedOutput

    /** A distinct output file was written and verified (real remux or encode). */
    data class GeneratedFile(
        val uri: Uri,
        val file: File,
        val verification: OutputVerificationReport
    ) : MaterializedOutput
}

/** Why a keep-original decision was allowed to reuse the source, or why it was blocked. */
enum class OriginalReuseBlockReason {
    NOT_KEEP_ORIGINAL_DECISION,
    USER_REQUESTED_REMUX_ONLY,
    PRIVACY_STRIP_REQUIRED,
    CONTAINER_NORMALIZATION_REQUIRED,
    SOURCE_NOT_READABLE,
    DISTINCT_OUTPUT_REQUIRED
}

sealed interface OriginalReuseDecision {
    /** All guards passed: the original may be surfaced as the result with no copy. */
    data class Eligible(val containerMime: String?) : OriginalReuseDecision

    /** A guard failed: the full remux (copy + verification) path MUST run. */
    data class Blocked(val reason: OriginalReuseBlockReason) : OriginalReuseDecision
}

/**
 * Single audited policy deciding whether a keep-original outcome may reuse the source directly.
 *
 * Pure and unit-testable: every environmental fact (privacy mode, container MIME, readability)
 * is passed in by the caller, which must derive them from REAL probes (ContentResolver type +
 * an actual read-open check), never from a filename extension alone.
 *
 * The original may be reused ONLY when every guard passes. Any doubt fails CLOSED to the full
 * copy+verify remux path — this policy can only ever skip work for already-decided keep-original
 * outcomes; it can never change a compression/remux DECISION, touch a quality bar, or weaken
 * verification of real outputs.
 */
object OriginalReusePolicy {

    /** Container MIME types the app's pipeline handles natively with no normalization needed. */
    private val COMPATIBLE_CONTAINER_MIMES = setOf(
        "video/mp4",
        "video/quicktime",
        "video/3gpp",
        "video/3gpp2"
    )

    fun evaluate(
        /** True only when the pipeline has ALREADY decided this item keeps its original bytes. */
        isKeepOriginalDecision: Boolean,
        /** True when the user explicitly selected Remux Only (the remux IS the deliverable). */
        userRequestedRemuxOnly: Boolean,
        /** The batch's metadata privacy mode; anything but PRESERVE_ALL needs the rewrite. */
        privacyMode: MetadataPrivacyMode,
        /** Container MIME from ContentResolver/probe (NOT from the filename extension). */
        resolvedContainerMime: String?,
        /** True when the source URI opened for READ at decision time. */
        sourceReadableNow: Boolean,
        /** True when some caller explicitly requires a distinct new file regardless. */
        distinctOutputRequired: Boolean = false
    ): OriginalReuseDecision {
        if (!isKeepOriginalDecision) {
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.NOT_KEEP_ORIGINAL_DECISION)
        }
        if (userRequestedRemuxOnly) {
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.USER_REQUESTED_REMUX_ONLY)
        }
        if (privacyMode != MetadataPrivacyMode.PRESERVE_ALL) {
            // The copy is the mechanism that strips metadata; reuse would silently skip the strip.
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.PRIVACY_STRIP_REQUIRED)
        }
        if (resolvedContainerMime == null ||
            resolvedContainerMime.lowercase() !in COMPATIBLE_CONTAINER_MIMES
        ) {
            // Unknown or non-MP4-family container: normalization may be the remux's real value.
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.CONTAINER_NORMALIZATION_REQUIRED)
        }
        if (!sourceReadableNow) {
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.SOURCE_NOT_READABLE)
        }
        if (distinctOutputRequired) {
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.DISTINCT_OUTPUT_REQUIRED)
        }
        return OriginalReuseDecision.Eligible(resolvedContainerMime)
    }

    /**
     * Honest verification report for a retained original. This is NOT a stream-copy verification
     * (no copy exists); it records that the source itself was probed readable/playable at
     * retention time. verified=false (source unreadable) fails closed to OUTPUT_VALIDATION_FAILED
     * in the classifier. replacementSafe is ALWAYS false: there is nothing to replace, and the
     * replacement flow must remain a no-op for reused sources.
     */
    fun retentionReport(
        sourcePlayable: Boolean,
        sourceSizeBytes: Long,
        containerMime: String?
    ): OutputVerificationReport = OutputVerificationReport(
        verdict = if (sourcePlayable) {
            "Original Retained (already optimal; no copy written)"
        } else {
            "Original Retention Failed (source unreadable)"
        },
        playability = if (sourcePlayable) "opens" else "failed",
        video = "unchanged (original retained)",
        fps = "unchanged (original retained)",
        videoBitrate = "unchanged (original retained)",
        videoCodec = "unchanged (original retained)",
        audioCodec = "unchanged (original retained)",
        audioDetails = "unchanged (original retained)",
        audioBitrate = "unchanged (original retained)",
        hdr = "unchanged (original retained)",
        colorStandard = "unchanged (original retained)",
        colorRange = "unchanged (original retained)",
        mediaStoreDate = "unchanged (original retained)",
        mp4Date = "unchanged (original retained)",
        location = "unchanged (original retained)",
        rotation = "unchanged (original retained)",
        fileSize = "${formatFileSize(sourceSizeBytes)} (original retained, 0 bytes written)",
        replacementSafe = false,
        replacementBlockReason = "original retained — replacement is a no-op by design",
        criticalFieldsComplete = sourcePlayable,
        verified = sourcePlayable,
        durationParity = "unchanged (original retained; container $containerMime)"
    )
}
