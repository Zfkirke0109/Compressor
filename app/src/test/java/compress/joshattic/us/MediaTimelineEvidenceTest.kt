package compress.joshattic.us

import org.junit.Assert.*
import org.junit.Test

class MediaTimelineEvidenceTest {
    @Test fun missingOneFrameIsNotAOnePercentTolerance() {
        assertFalse(MediaTimelineEvidence.compare(longArrayOf(0, 33000, 66000), longArrayOf(0, 66000)).matches)
    }
    @Test fun internalRetimingFailsEvenWhenDurationAndCountAgree() {
        assertFalse(MediaTimelineEvidence.compare(longArrayOf(0, 33000, 99000), longArrayOf(0, 66000, 99000)).matches)
    }
    @Test fun aUniformPtsOriginShiftIsNotFrameLoss() {
        assertTrue(MediaTimelineEvidence.compare(longArrayOf(7000000,7033000,7099000),longArrayOf(0,33000,99000)).matches)
    }
    @Test fun duplicatePresentationTimesAreAmbiguousEvidence() {
        assertFalse(MediaTimelineEvidence.compare(longArrayOf(0,0,33000),longArrayOf(0,0,33000)).matches)
    }

    @Test fun aClampedFirstVideoTimestampCanBeRestoredWithoutAcceptingTheBadTimeline() {
        val source = longArrayOf(14_277, 47_610, 80_944, 114_277)
        val candidate = source.copyOf().also { it[0] = 0 }
        val before = MediaTimelineEvidence.compare(source, candidate)
        assertFalse(before.matches)
        assertEquals(14_277L, before.firstVideoPtsToRestoreUs)
        candidate[0] = checkNotNull(before.firstVideoPtsToRestoreUs)
        assertTrue(MediaTimelineEvidence.compare(source, candidate).matches)
    }

    @Test fun aMissingRetimedOrAmbiguousFrameCannotUseTimestampRestoration() {
        val source = longArrayOf(3_633, 20_300, 36_966, 53_633)
        assertNull(MediaTimelineEvidence.compare(source, longArrayOf(0, 20_300, 53_633)).firstVideoPtsToRestoreUs)
        assertNull(MediaTimelineEvidence.compare(source, longArrayOf(0, 20_300, 38_966, 53_633)).firstVideoPtsToRestoreUs)
        assertNull(MediaTimelineEvidence.compare(source, longArrayOf(0, 20_300, 20_300, 53_633)).firstVideoPtsToRestoreUs)
        val uniformShift = MediaTimelineEvidence.compare(source, source.map { it - 3_633 }.toLongArray())
        assertTrue(uniformShift.matches)
        assertNull(uniformShift.firstVideoPtsToRestoreUs)
    }

    private fun packets(shift: Long=0, flags: Int=1) = listOf(0L,23000L,46000L).map {
        AudioPacketEvidence.Packet(byteArrayOf(1,2,3),it+shift,flags)
    }.iterator()
    @Test fun identicalAudioPayloadWithChangedDecoderConfigIsNotIdenticalAudio() {
        assertFalse(AudioPacketEvidence.compare(packets(),packets(),"configA","configB",0,0).identical)
    }
    @Test fun equalAudioPacketsDoNotHideAnAudioVideoSyncShift() {
        assertFalse(AudioPacketEvidence.compare(packets(),packets(50000),"configA","configA",0,0).identical)
    }
    @Test fun restoringOnlyTheClampedVideoOriginCanRecoverTheProvenAudioOffset() {
        assertFalse(AudioPacketEvidence.compare(packets(), packets(), "configA", "configA", 14_277, 0).identical)
        assertTrue(AudioPacketEvidence.compare(packets(), packets(), "configA", "configA", 14_277, 14_277).identical)
    }
    @Test fun commonAudioVideoOriginShiftAndCopiedLowBitrateAudioArePreserved() {
        assertTrue(AudioPacketEvidence.compare(packets(7000000),packets(),"configA","configA",7000000,0).identical)
    }
    @Test fun unknownCodecConfigCannotProveAudioIdentity() {
        assertFalse(AudioPacketEvidence.compare(packets(),packets(),null,null,0,0).identical)
    }
    @Test fun changedPacketFlagsCannotProveTheSamePresentation() {
        assertFalse(AudioPacketEvidence.compare(packets(),packets(flags=0),"configA","configA",0,0).identical)
    }
}
