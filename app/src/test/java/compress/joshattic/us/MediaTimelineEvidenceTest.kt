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

    /** 168 frames from 97,488 us at an uneven 24 fps on a 60 fps grid: b287 job_782a717d3217's shape. */
    private fun capturedShape(): LongArray {
        var time = 97_488L
        return LongArray(168) { i -> time.also { time += if (i % 2 == 0) 33_333L else 50_000L } }
    }

    @Test fun twoFramesMissingFromTheEndAreNeverAnOriginShiftOrARestorableTimestamp() {
        val source = capturedShape()
        // What the capture recorded: 166 samples, the first at zero, the rest where they were.
        val candidate = source.copyOfRange(0, 166).also { it[0] = 0 }
        val result = MediaTimelineEvidence.compare(source, candidate)
        assertFalse(result.matches)
        assertEquals("frame count 168 -> 166", result.reason)
        assertEquals(168, result.sourceFrames)
        assertEquals(166, result.outputFrames)
        assertEquals(97_488L, result.sourceOriginUs)
        assertEquals(0L, result.outputOriginUs)
        assertEquals(97_488L, result.maxSkewUs)
        assertEquals(1, result.firstMismatch)
        // Putting the first timestamp back does not make 166 frames into 168.
        assertNull(result.firstVideoPtsToRestoreUs)
        val restored = candidate.copyOf().also { it[0] = 97_488 }
        val again = MediaTimelineEvidence.compare(source, restored)
        assertFalse(again.matches)
        assertEquals("frame count 168 -> 166", again.reason)
        assertNull(again.firstVideoPtsToRestoreUs)
    }

    @Test fun twoFramesMissingAnywhereAreRejectedWhateverElseAgrees() {
        val source = capturedShape()
        for (dropped in listOf(listOf(0, 1), listOf(80, 81), listOf(40, 120), listOf(166, 167))) {
            val candidate = source.filterIndexed { i, _ -> i !in dropped }.toLongArray()
            val result = MediaTimelineEvidence.compare(source, candidate)
            assertFalse("$dropped", result.matches)
            assertNull("$dropped", result.firstVideoPtsToRestoreUs)
            // The same frames presented from another origin are still two short.
            assertFalse("$dropped", MediaTimelineEvidence.compare(source, candidate.map { it - candidate[0] }.toLongArray()).matches)
        }
    }

    @Test fun everyFrameWithOnlyTheFirstTimestampMovedIsStillRecognisedAtThatLength() {
        // The repair b287 made twice (job_4e26f86286b1 at 14,277 us, job_42f517cc2b8a at 3,633 us),
        // at this source's offset and frame count: all 168 frames, the first moved to zero.
        val source = capturedShape()
        val candidate = source.copyOf().also { it[0] = 0 }
        val before = MediaTimelineEvidence.compare(source, candidate)
        assertFalse(before.matches)
        assertEquals(97_488L, before.firstVideoPtsToRestoreUs)
        assertTrue(MediaTimelineEvidence.compare(source, candidate.also { it[0] = 97_488 }).matches)
        // The whole timeline moved together is a new origin, not a defect to repair.
        val shifted = MediaTimelineEvidence.compare(source, source.map { it - 97_488 }.toLongArray())
        assertTrue(shifted.matches)
        assertNull(shifted.firstVideoPtsToRestoreUs)
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
