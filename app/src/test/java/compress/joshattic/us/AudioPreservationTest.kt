package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audio half of a PL claim is stated separately, and never called lossless without proof. */
class AudioPreservationTest {

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
        assertEquals("re-encoded (lossy mode)", AudioPreservation.describe(BatchQualityMode.HIGH_QUALITY, true, false, 0, false))
    }

    @Test
    fun countedComparisonReportsHowManyPacketsWereChecked() {
        fun seq(vararg p: String) = p.map { it.toByteArray() }.iterator()
        val o = AudioTrackIdentity.compareCounted(seq("a", "bc", "d"), seq("a", "bc", "d"))
        assertEquals(AudioTrackIdentity.Result.IDENTICAL, o.result)
        assertEquals(3, o.packets)
    }
}
