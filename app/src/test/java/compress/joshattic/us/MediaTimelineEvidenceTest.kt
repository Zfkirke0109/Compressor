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

    /** AAC at 48 kHz: one packet every 21,333 us, each with its own payload. */
    private fun aac(count: Int, first: Int = 0, shiftUs: Long = 0) = (first until first + count).map {
        AudioPacketEvidence.Packet(byteArrayOf((it % 127).toByte(), (it / 127).toByte(), 7), it * 21_333L + shiftUs, 1)
    }

    @Test fun anOutputThatLacksTheSourcesFirstPacketSaysSoBesideTheConfigurationReason() {
        // b213 job_6d1173f4c0f9 read only "decoder configuration differs", with a skew of 21,334 us
        // (one AAC frame) and different payload hashes. Whether a packet was missing was not recorded.
        val result = AudioPacketEvidence.compare(aac(342).iterator(), aac(341, first = 1).iterator(), "configA", "configB", 0, 0)
        assertFalse(result.identical)
        assertEquals("decoder configuration differs", result.reason)
        assertEquals(342, result.sourcePackets)
        assertEquals(341, result.outputPackets)
        assertEquals(341, result.packets)
        assertEquals(0L, result.sourceFirstPtsUs)
        assertEquals(21_333L, result.outputFirstPtsUs)
        assertEquals("payload differs", result.firstMismatchKind)
        assertEquals(0, result.firstMismatch)
        assertEquals(21_333L, result.maxSkewUs)
        assertTrue(result.outputLacksFirstSourcePacket)
        assertFalse(result.outputHasExtraFirstPacket)
    }

    @Test fun eachPayloadHashCoversEveryPacketItsTrackHolds() {
        fun sha256(packets: List<AudioPacketEvidence.Packet>): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            packets.forEach { digest.update(it.payload) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        // An output without the source's last packet: the counts differ, so the hashes beside them
        // must too. They used to stop where the shorter track ended and read equal.
        val shortOutput = AudioPacketEvidence.compare(aac(50).iterator(), aac(49).iterator(), "config", "config", 0, 0)
        assertEquals(50 to 49, shortOutput.sourcePackets to shortOutput.outputPackets)
        assertEquals(sha256(aac(50)), shortOutput.sourceHash)
        assertEquals(sha256(aac(49)), shortOutput.outputHash)
        assertNotEquals(shortOutput.sourceHash, shortOutput.outputHash)
        // The mirror case, several packets long, and the one-packet offset of b213's shape.
        val longOutput = AudioPacketEvidence.compare(aac(47).iterator(), aac(50).iterator(), "config", "config", 0, 0)
        assertEquals(sha256(aac(47)) to sha256(aac(50)), longOutput.sourceHash to longOutput.outputHash)
        val offset = AudioPacketEvidence.compare(aac(342).iterator(), aac(341, first = 1).iterator(), "configA", "configB", 0, 0)
        assertEquals(sha256(aac(342)) to sha256(aac(341, first = 1)), offset.sourceHash to offset.outputHash)
        // Equal tracks still hash equal.
        val same = AudioPacketEvidence.compare(aac(50).iterator(), aac(50).iterator(), "config", "config", 0, 0)
        assertEquals(sha256(aac(50)) to sha256(aac(50)), same.sourceHash to same.outputHash)
    }

    @Test fun anExtraLeadingPacketIsToldApartFromAMissingOne() {
        val result = AudioPacketEvidence.compare(aac(341, first = 1).iterator(), aac(342).iterator(), "config", "config", 0, 0)
        assertFalse(result.identical)
        assertEquals("payload differs", result.reason)
        assertEquals(341 to 342, result.sourcePackets to result.outputPackets)
        assertTrue(result.outputHasExtraFirstPacket)
        assertFalse(result.outputLacksFirstSourcePacket)
    }

    @Test fun streamsThatAreNotOnePacketApartClaimNoOffset() {
        // Identical: nothing to explain.
        val same = AudioPacketEvidence.compare(aac(50).iterator(), aac(50).iterator(), "config", "config", 0, 0)
        assertTrue(same.identical)
        assertNull(same.firstMismatchKind)
        assertEquals(50 to 50, same.sourcePackets to same.outputPackets)
        assertFalse(same.outputLacksFirstSourcePacket || same.outputHasExtraFirstPacket)
        // The same packets, later against the video: timing alone, no packet missing.
        val late = AudioPacketEvidence.compare(aac(50).iterator(), aac(50, shiftUs = 21_333).iterator(), "config", "config", 0, 0)
        assertEquals("presentation time differs against video by 21333us", late.firstMismatchKind)
        assertFalse(late.outputLacksFirstSourcePacket || late.outputHasExtraFirstPacket)
        // One packet rewritten in the middle of equal-length streams.
        val edited = aac(50).toMutableList().also { it[20] = AudioPacketEvidence.Packet(byteArrayOf(9, 9, 9), it[20].ptsUs, 1) }
        val middle = AudioPacketEvidence.compare(aac(50).iterator(), edited.iterator(), "config", "config", 0, 0)
        assertEquals(20, middle.firstMismatch)
        assertEquals("payload differs", middle.firstMismatchKind)
        assertFalse(middle.outputLacksFirstSourcePacket || middle.outputHasExtraFirstPacket)
        // Two packets short at the front is not "lacks the first packet".
        val two = AudioPacketEvidence.compare(aac(50).iterator(), aac(48, first = 2).iterator(), "config", "config", 0, 0)
        assertEquals(50 to 48, two.sourcePackets to two.outputPackets)
        assertFalse(two.outputLacksFirstSourcePacket)
        // Digital silence, one packet short and late: every packet carries the same bytes, so it
        // matches at any offset and nothing says WHICH packet is missing.
        fun silence(count: Int, shiftUs: Long = 0) = (0 until count).map { AudioPacketEvidence.Packet(byteArrayOf(0, 0, 0), it * 21_333L + shiftUs, 1) }
        val silent = AudioPacketEvidence.compare(silence(50).iterator(), silence(49, shiftUs = 21_333).iterator(), "config", "config", 0, 0)
        assertEquals(50 to 49, silent.sourcePackets to silent.outputPackets)
        assertEquals("presentation time differs against video by 21333us", silent.firstMismatchKind)
        assertFalse(silent.outputLacksFirstSourcePacket || silent.outputHasExtraFirstPacket)
        // The last packet missing: counts differ by one, but the packets are not offset.
        val tail = AudioPacketEvidence.compare(aac(50).iterator(), aac(49).iterator(), "config", "config", 0, 0)
        assertEquals("packet count differs", tail.reason)
        assertEquals("packet count differs", tail.firstMismatchKind)
        assertEquals(49, tail.firstMismatch)
        assertFalse(tail.outputLacksFirstSourcePacket || tail.outputHasExtraFirstPacket)
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
