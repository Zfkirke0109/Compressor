package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard matrix for the keep-original remux fast path. The policy may only ever SKIP redundant
 * work for an already-decided keep-original outcome — any doubt fails CLOSED to the full
 * copy+verify remux. These tests cover the eleven-requirement matrix from
 * docs/pr23/REMUX_ACCELERATION_INVESTIGATION.md that is expressible at unit level.
 */
class OriginalReusePolicyTest {

    private fun eval(
        keep: Boolean = true,
        remuxOnly: Boolean = false,
        privacy: MetadataPrivacyMode = MetadataPrivacyMode.PRESERVE_ALL,
        mime: String? = "video/mp4",
        readable: Boolean = true,
        distinct: Boolean = false
    ) = OriginalReusePolicy.evaluate(
        isKeepOriginalDecision = keep,
        userRequestedRemuxOnly = remuxOnly,
        privacyMode = privacy,
        resolvedContainerMime = mime,
        sourceReadableNow = readable,
        distinctOutputRequired = distinct
    )

    private fun blockedReason(d: OriginalReuseDecision): OriginalReuseBlockReason =
        (d as OriginalReuseDecision.Blocked).reason

    @Test
    fun eligibleOnlyWhenEveryGuardPasses() {
        val d = eval()
        assertTrue(d is OriginalReuseDecision.Eligible)
        assertEquals("video/mp4", (d as OriginalReuseDecision.Eligible).containerMime)
    }

    @Test
    fun notAKeepOriginalDecisionIsAlwaysBlocked() {
        assertEquals(
            OriginalReuseBlockReason.NOT_KEEP_ORIGINAL_DECISION,
            blockedReason(eval(keep = false))
        )
    }

    @Test
    fun explicitRemuxOnlyAlwaysProducesARealRemux() {
        // The remux IS the user's deliverable — never reuse the source for it.
        assertEquals(
            OriginalReuseBlockReason.USER_REQUESTED_REMUX_ONLY,
            blockedReason(eval(remuxOnly = true))
        )
    }

    @Test
    fun anyPrivacyStrippingModeForcesTheFullRemux() {
        // The copy is the metadata-stripping mechanism; reuse would silently skip the strip.
        for (mode in MetadataPrivacyMode.values()) {
            if (mode == MetadataPrivacyMode.PRESERVE_ALL) continue
            assertEquals(
                "mode $mode must block reuse",
                OriginalReuseBlockReason.PRIVACY_STRIP_REQUIRED,
                blockedReason(eval(privacy = mode))
            )
        }
    }

    @Test
    fun containerCompatibilityIsProbedNeverAssumed() {
        // Unknown MIME fails closed — extension-based inference is not accepted.
        assertEquals(
            OriginalReuseBlockReason.CONTAINER_NORMALIZATION_REQUIRED,
            blockedReason(eval(mime = null))
        )
        // Non-MP4-family containers keep the normalizing remux.
        for (bad in listOf("video/webm", "video/x-matroska", "video/avi", "application/octet-stream")) {
            assertEquals(
                "mime $bad must block reuse",
                OriginalReuseBlockReason.CONTAINER_NORMALIZATION_REQUIRED,
                blockedReason(eval(mime = bad))
            )
        }
        // MP4 family is eligible (case-insensitive).
        for (ok in listOf("video/mp4", "video/quicktime", "VIDEO/MP4", "video/3gpp")) {
            assertTrue("mime $ok should be eligible", eval(mime = ok) is OriginalReuseDecision.Eligible)
        }
    }

    @Test
    fun unreadableSourceFailsClosed() {
        assertEquals(
            OriginalReuseBlockReason.SOURCE_NOT_READABLE,
            blockedReason(eval(readable = false))
        )
    }

    @Test
    fun distinctOutputRequirementForcesTheFullRemux() {
        assertEquals(
            OriginalReuseBlockReason.DISTINCT_OUTPUT_REQUIRED,
            blockedReason(eval(distinct = true))
        )
    }

    @Test
    fun retentionReportIsHonestAndNeverReplacementSafe() {
        val ok = OriginalReusePolicy.retentionReport(
            sourcePlayable = true, sourceSizeBytes = 1_000_000L, containerMime = "video/mp4"
        )
        assertTrue(ok.verified)
        // Nothing was written; replacement must remain a no-op forever.
        assertFalse(ok.replacementSafe)
        assertTrue(ok.verdict.contains("Original Retained"))
        assertTrue(ok.fileSize.contains("0 bytes written"))

        // Unreadable source -> verified=false -> classifier fails closed.
        val bad = OriginalReusePolicy.retentionReport(
            sourcePlayable = false, sourceSizeBytes = 1_000_000L, containerMime = "video/mp4"
        )
        assertFalse(bad.verified)
        assertFalse(bad.replacementSafe)
    }
}
