package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio half of a PL claim is stated separately, and never called lossless without proof. */
class AudioPreservationTest {

    @Test
    fun measuredPacketDifferenceOutranksAnInferredCopy() {
        for (mode in listOf(BatchQualityMode.HIGH_QUALITY, BatchQualityMode.STORAGE_SAVER)) {
            assertEquals(AudioPreservation.LOSSY_PACKETS_DIFFER,
                AudioPreservation.describe(mode, true, false, 3, true, packetsDiffer = true))
        }
        assertEquals(AudioPreservation.RE_ENCODED_NOT_VALIDATED,
            AudioPreservation.describe(BatchQualityMode.PERCEPTUAL_LOSSLESS, true, false, 3, true, packetsDiffer = true))
    }

    @Test
    fun aProvenCopyIsCalledBitIdenticalWithItsPacketCount() {
        assertEquals(
            "bit-identical copy of the source's compressed audio (5613 packets compared)",
            AudioPreservation.describe(BatchQualityMode.PERCEPTUAL_LOSSLESS, true, packetsIdentical = true, packetsCompared = 5613, inferredStreamCopy = false)
        )
    }

    @Test
    fun aReEncodeInPerceptuallyLosslessIsNeverCalledPerceptuallyLossless() {
        val d = AudioPreservation.describe(BatchQualityMode.PERCEPTUAL_LOSSLESS, true, false, 0, inferredStreamCopy = false)
        assertEquals(AudioPreservation.RE_ENCODED_NOT_VALIDATED, d)
        assertTrue(d!!.contains("not validated"))
    }

    @Test
    fun anInferredCopyIsLabelledAsInferred() {
        assertEquals(
            AudioPreservation.INFERRED_COPY,
            AudioPreservation.describe(BatchQualityMode.PERCEPTUAL_LOSSLESS, true, false, 0, inferredStreamCopy = true)
        )
    }

    @Test
    fun noAudioAndLossyModesAreNamedPlainly() {
        assertEquals(AudioPreservation.NO_AUDIO, AudioPreservation.describe(BatchQualityMode.PERCEPTUAL_LOSSLESS, false, false, 0, false))
    }

    @Test
    fun lossyModesAreDescribedByWhatTheOutputShowsNotByTheModeName() {
        // b177 F5: both HDR High Quality jobs requested audio=copy and were recorded as re-encoded.
        assertEquals(
            AudioPreservation.bitIdentical(6_000),
            AudioPreservation.describe(BatchQualityMode.HIGH_QUALITY, true, packetsIdentical = true, packetsCompared = 6_000, inferredStreamCopy = false)
        )
        assertEquals(AudioPreservation.INFERRED_COPY, AudioPreservation.describe(BatchQualityMode.HIGH_QUALITY, true, false, 0, inferredStreamCopy = true))
        assertEquals(
            AudioPreservation.LOSSY_PACKETS_DIFFER,
            AudioPreservation.describe(BatchQualityMode.STORAGE_SAVER, true, false, 0, false, packetsDiffer = true)
        )
        // Nothing shown either way: not called a copy, and not asserted to be a re-encode.
        val unknown = AudioPreservation.describe(BatchQualityMode.HIGH_QUALITY, true, false, 0, false)
        assertEquals(AudioPreservation.LOSSY_NOT_SHOWN_AS_COPY, unknown)
        assertTrue(!unknown!!.startsWith("re-encoded"))
    }

    @Test
    fun theRequestIsRecordedSeparately() {
        assertEquals("copy", AudioPreservation.requested(ResolvedEncodePlan.AudioPlan.Copy(256_000)))
        assertEquals("reencode", AudioPreservation.requested(ResolvedEncodePlan.AudioPlan.Reencode(256_000)))
        assertEquals("none", AudioPreservation.requested(ResolvedEncodePlan.AudioPlan.None))
        assertEquals(null, AudioPreservation.requested(null))
    }

    @Test
    fun countedComparisonReportsHowManyPacketsWereChecked() {
        fun seq(vararg p: String) = p.map { it.toByteArray() }.iterator()
        val o = AudioTrackIdentity.compareCounted(seq("a", "bc", "d"), seq("a", "bc", "d"))
        assertEquals(AudioTrackIdentity.Result.IDENTICAL, o.result)
        assertEquals(3, o.packets)
    }
}
