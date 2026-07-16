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
    /**
     * The untouched original is the result. No copy was written; NOTHING was verified as a copy —
     * a [ReusedSource] carries only [RetainedSourceValidation] (source readability at decision
     * time), never an [OutputVerificationReport]. The type system enforces that retained sources
     * cannot masquerade as verified generated outputs.
     */
    data class ReusedSource(
        val sourceUri: Uri,
        val decision: OriginalReuseDecision.Eligible,
        val validation: RetainedSourceValidation
    ) : MaterializedOutput

    /** A distinct output file was written and verified (real remux or encode). */
    data class GeneratedFile(
        val uri: Uri,
        val file: File,
        val verification: OutputVerificationReport
    ) : MaterializedOutput
}

/**
 * Honest record of what was ACTUALLY checked when a source was retained: that the source URI
 * opened for read (and optionally probed playable) at decision time, its size, and its probed
 * container MIME. Deliberately NOT an [OutputVerificationReport]: no copy exists, so no codec/
 * duration/fps/HDR/metadata/track parity was measured, and none may be implied. Diagnostics
 * derived from this type must say "Original Retained", never "Verified".
 */
data class RetainedSourceValidation(
    val readableAtDecisionTime: Boolean,
    val playableAtDecisionTime: Boolean,
    val sizeBytes: Long,
    val containerMime: String?,
    val validatedAtEpochMs: Long
) {
    /** Diagnostic verdict string — distinct vocabulary from OutputVerifier verdicts. */
    val verdict: String
        get() = if (readableAtDecisionTime) {
            "Original Retained (source readable; no copy written; not output-verified)"
        } else {
            "Original Retention Failed (source unreadable)"
        }
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

    /**
     * Normalizes a ContentResolver MIME for comparison: trims, strips parameters
     * ("video/mp4; codecs=avc1" -> "video/mp4"), lowercases. Null/blank stays null so unknown
     * values keep failing closed. Document-provider caveat: getType() may legitimately return
     * null or application/octet-stream for playable files — those fail closed to the full remux
     * path, which is SAFE (only slower); compatibility is never inferred from a filename extension.
     */
    fun normalizeMime(raw: String?): String? {
        val cleaned = raw?.trim()?.substringBefore(';')?.trim()?.lowercase()
        return cleaned?.takeIf { it.isNotEmpty() }
    }

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
        val normalizedMime = normalizeMime(resolvedContainerMime)
        if (normalizedMime == null || normalizedMime !in COMPATIBLE_CONTAINER_MIMES) {
            // Unknown or non-MP4-family container: normalization may be the remux's real value.
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.CONTAINER_NORMALIZATION_REQUIRED)
        }
        if (!sourceReadableNow) {
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.SOURCE_NOT_READABLE)
        }
        if (distinctOutputRequired) {
            return OriginalReuseDecision.Blocked(OriginalReuseBlockReason.DISTINCT_OUTPUT_REQUIRED)
        }
        return OriginalReuseDecision.Eligible(normalizedMime)
    }

    /**
     * Builds the honest retained-source validation record. Deliberately NOT an
     * OutputVerificationReport: no generated output exists, so nothing that OutputVerifier
     * measures (codec/duration/fps/HDR/metadata/track parity) was measured, and none of those
     * fields may be synthesized. An unreadable source (readableAtDecisionTime=false) fails
     * closed to OUTPUT_VALIDATION_FAILED in the classifier.
     */
    fun retainedSourceValidation(
        sourceReadable: Boolean,
        sourceSizeBytes: Long,
        containerMime: String?,
        nowEpochMs: Long
    ): RetainedSourceValidation = RetainedSourceValidation(
        readableAtDecisionTime = sourceReadable,
        // The read-open check is our playability proxy at decision time; no frames are decoded.
        playableAtDecisionTime = sourceReadable,
        sizeBytes = sourceSizeBytes,
        containerMime = normalizeMime(containerMime),
        validatedAtEpochMs = nowEpochMs
    )
}
