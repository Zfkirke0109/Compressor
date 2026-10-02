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
    private fun packets(shift: Long=0, flags: Int=1) = listOf(0L,23000L,46000L).map {
        AudioPacketEvidence.Packet(byteArrayOf(1,2,3),it+shift,flags)
    }.iterator()
    @Test fun identicalAudioPayloadWithChangedDecoderConfigIsNotIdenticalAudio() {
        assertFalse(AudioPacketEvidence.compare(packets(),packets(),"configA","configB",0,0).identical)
    }
    @Test fun equalAudioPacketsDoNotHideAnAudioVideoSyncShift() {
        assertFalse(AudioPacketEvidence.compare(packets(),packets(50000),"configA","configA",0,0).identical)
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
