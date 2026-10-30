package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** b161 reported 2 AV1/VP9 WebM keep-original decisions as failures because no stream copy could be made. */
class ContainerCannotBeCopiedTest {

    private fun evaluate(copyImpossible: Boolean, privacy: MetadataPrivacyMode = MetadataPrivacyMode.PRESERVE_ALL, readable: Boolean = true) =
        OriginalReusePolicy.evaluate(
            isKeepOriginalDecision = true,
            userRequestedRemuxOnly = false,
            privacyMode = privacy,
            resolvedContainerMime = "video/webm",
            sourceReadableNow = readable,
            containerCannotBeCopied = copyImpossible
        )

    @Test
    fun webmStillTriesTheCopyFirst() {
        assertEquals(
            OriginalReuseDecision.Blocked(OriginalReuseBlockReason.CONTAINER_NORMALIZATION_REQUIRED),
            evaluate(copyImpossible = false)
        )
    }

    @Test
    fun onceTheCopyIsImpossibleTheOriginalIsKept() {
        assertTrue(evaluate(copyImpossible = true) is OriginalReuseDecision.Eligible)
    }

    @Test
    fun privacyStrippingAndUnreadableSourcesStillBlock() {
        assertEquals(
            OriginalReuseDecision.Blocked(OriginalReuseBlockReason.PRIVACY_STRIP_REQUIRED),
            evaluate(copyImpossible = true, privacy = MetadataPrivacyMode.fromLabel("Remove location only"))
        )
        assertEquals(
            OriginalReuseDecision.Blocked(OriginalReuseBlockReason.SOURCE_NOT_READABLE),
            evaluate(copyImpossible = true, readable = false)
        )
    }
}
