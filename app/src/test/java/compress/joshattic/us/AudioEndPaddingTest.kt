package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A copied AAC track whose end padding differs from its source's (decided November 12).
 *
 * It does not pass as it is, and no tolerance was added: identical audio still means the same
 * packets under the same configuration, the padding included. Such a candidate is rewritten once
 * by the platform's muxer, the rewrite the first timestamp repair already makes, and then has to
 * pass the unchanged check.
 *
 * On the S26 (v1.6.299) Media3's file read 2 samples of end padding where the source had 0, with
 * the packets, the AudioSpecificConfig and the delay unchanged, and the platform's muxer wrote the
 * track back with 0. These are the pure halves of the rule: what counts as "only the end padding",
 * and when a rewrite is tried. That the verifier still refuses such a copy is held by
 * ScientificEvidenceSafetyTest; the rewrite itself runs on a phone (EditListTimelineDeviceTest
 * prints what it gives).
 */
class AudioEndPaddingTest {

    // The S26's values for the synthetic source and for Media3's copy of it.
    private val asc = byteArrayOf(0x11, 0x90.toByte(), 0x56, 0xe5.toByte(), 0x00)

    private fun config(padding: Int? = 0, delay: Int? = 1024, csd0: ByteArray? = asc, rate: Int? = 48_000,
                       channels: Int? = 2, mime: String? = "audio/mp4a-latm", csd1: ByteArray? = null) =
        AudioTrackIdentity.Config(mime, channels, rate, delay, padding, listOf(csd0, csd1, null))

    /** The hash as the builds before this one took it, written out again from the MediaFormat keys. */
    private fun earlierHash(mime: String?, channels: Int?, rate: Int?, delay: Int?, padding: Int?, csd: List<ByteArray?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val fields = listOf(
            "mime" to mime, "channel-count" to (channels?.toString() ?: "unknown"), "sample-rate" to (rate?.toString() ?: "unknown"),
            "encoder-delay" to (delay?.toString() ?: "0"), "encoder-padding" to (padding?.toString() ?: "0")
        )
        for ((key, value) in fields) digest.update("$key=$value;".toByteArray(Charsets.UTF_8))
        for (bytes in csd) { digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array()); bytes?.let(digest::update) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun theConfigurationHashIsTheOneEarlierBuildsRecorded() {
        // Captures hold these hashes; a build that hashed differently could not be compared with them.
        val cases = listOf(
            config(), config(padding = 2), config(padding = null, delay = null), config(rate = null, channels = null),
            config(mime = null), config(csd1 = byteArrayOf(1, 2, 3)), config(csd0 = byteArrayOf(0x12, 0x10), rate = 44_100)
        )
        for (c in cases) {
            assertEquals(earlierHash(c.mime, c.channels, c.sampleRate, c.encoderDelay, c.encoderPadding, c.csd), c.hash())
        }
        // Without codec-specific data nothing is proven, as before.
        assertNull(config(csd0 = null).hash())
        assertNull(config(csd0 = ByteArray(0)).hash())
        assertNull(config(csd0 = null).hash(withEncoderPadding = false))
        // What the record shows of a configuration is unchanged too.
        assertEquals(
            mapOf("mime" to "audio/mp4a-latm", "channels" to 2, "sampleRate" to 48_000, "encoderDelay" to 1024,
                "encoderPadding" to 2, "csdSizes" to listOf(5, null, null), "csd0Hex" to "119056e500"),
            config(padding = 2).describe()
        )
        assertNull(config(mime = "audio/flac").describe()["csd0Hex"])
    }

    @Test fun onlyTheEndPaddingIsLeftOutOfTheSecondHash() {
        val source = config(padding = 0)
        assertNotEquals(source.hash(), config(padding = 2).hash())
        assertEquals(source.hash(withEncoderPadding = false), config(padding = 2).hash(withEncoderPadding = false))
        // Everything else still tells two tracks apart with the padding left out.
        for (other in listOf(config(delay = 2112), config(rate = 44_100), config(channels = 1), config(mime = "audio/opus"),
            config(csd0 = byteArrayOf(0x12, 0x10)), config(csd1 = byteArrayOf(9)))) {
            assertNotEquals(source.hash(withEncoderPadding = false), other.hash(withEncoderPadding = false))
        }
    }

    @Test fun twoConfigurationsDifferOnlyInTheEndPaddingWhenNothingElseDoes() {
        val source = config(padding = 0)
        fun only(output: AudioTrackIdentity.Config) = AudioTrackIdentity.Config.differOnlyInEncoderPadding(source, output)
        // What the S26 read: the source 0, Media3's copy 2.
        assertTrue(only(config(padding = 2)))
        assertTrue(AudioTrackIdentity.Config.differOnlyInEncoderPadding(config(padding = 2), source))
        // An absent padding is 0 to a decoder: against 0 nothing differs, against 2 only it does.
        assertFalse(only(config(padding = null)))
        assertTrue(AudioTrackIdentity.Config.differOnlyInEncoderPadding(config(padding = null), config(padding = 2)))
        // Equal configurations do not differ at all.
        assertFalse(only(config(padding = 0)))
        // The padding and something else.
        assertFalse(only(config(padding = 2, delay = 2112)))
        assertFalse(only(config(padding = 2, csd0 = byteArrayOf(0x12, 0x10))))
        assertFalse(only(config(padding = 2, rate = 44_100)))
        assertFalse(only(config(padding = 2, csd1 = byteArrayOf(9))))
        // Something else alone.
        assertFalse(only(config(delay = 2112)))
        // An unknown configuration proves nothing.
        assertFalse(only(config(padding = 2, csd0 = null)))
        assertFalse(AudioTrackIdentity.Config.differOnlyInEncoderPadding(config(csd0 = null), config(padding = 2)))
    }

    // ---- The whole comparison ------------------------------------------------------------------

    /** AAC at 48 kHz, the first packet one frame before zero as a 1,024-sample delay puts it. */
    private fun aac(count: Int = 329, shiftUs: Long = 0, change: Int? = null) = (0 until count).map {
        val payload = byteArrayOf((it % 127).toByte(), (it / 127).toByte(), if (it == change) 9 else 7)
        AudioPacketEvidence.Packet(payload, -21_333L + it * 21_333L + shiftUs, 1)
    }

    private fun compare(source: List<AudioPacketEvidence.Packet>, output: List<AudioPacketEvidence.Packet>,
                        sourceConfig: AudioTrackIdentity.Config = config(padding = 0),
                        outputConfig: AudioTrackIdentity.Config = config(padding = 2),
                        sourceOriginUs: Long = 97_488, outputOriginUs: Long = 97_488) =
        AudioTrackIdentity.compareTimed(source.iterator(), output.iterator(), sourceConfig, outputConfig, sourceOriginUs, outputOriginUs)

    @Test fun theS26sCopyDiffersOnlyInItsEndPaddingAndIsStillNotIdentical() {
        val copy = compare(aac(), aac())
        assertEquals(AudioTrackIdentity.Result.DIFFERENT, copy.result)
        assertEquals("decoder configuration differs", copy.evidence?.reason)
        assertTrue(copy.differsOnlyInEndPadding)
        assertEquals(
            "end padding differs (source 0, output 2 samples); the packets and the rest of the configuration match",
            copy.failureReason
        )
        assertEquals(329, copy.packets)
        // The same track with the source's padding is identical, and says nothing of padding.
        val same = compare(aac(), aac(), outputConfig = config(padding = 0))
        assertEquals(AudioTrackIdentity.Result.IDENTICAL, same.result)
        assertFalse(same.differsOnlyInEndPadding)
        assertNull(same.failureReason)
    }

    @Test fun anythingElseThatDiffersIsNotOnlyTheEndPadding() {
        // A packet's bytes.
        val payload = compare(aac(), aac(change = 200))
        assertFalse(payload.differsOnlyInEndPadding)
        assertEquals("decoder configuration differs", payload.failureReason)
        assertEquals(200, payload.evidence?.firstMismatch)
        // A packet more or fewer, at either end.
        assertFalse(compare(aac(), aac(328)).differsOnlyInEndPadding)
        assertFalse(compare(aac(328), aac()).differsOnlyInEndPadding)
        // The audio moved against the video: by the track, and by the video origin.
        assertFalse(compare(aac(), aac(shiftUs = 21_333)).differsOnlyInEndPadding)
        assertFalse(compare(aac(), aac(), outputOriginUs = 0).differsOnlyInEndPadding)
        // The padding with another configuration field, and another field alone.
        assertFalse(compare(aac(), aac(), outputConfig = config(padding = 2, delay = 2112)).differsOnlyInEndPadding)
        assertFalse(compare(aac(), aac(), outputConfig = config(padding = 0, delay = 2112)).differsOnlyInEndPadding)
        // A configuration that cannot be read, and tracks with nothing in them.
        val unknown = compare(aac(), aac(), outputConfig = config(padding = 2, csd0 = null))
        assertEquals(AudioTrackIdentity.Result.UNAVAILABLE, unknown.result)
        assertFalse(unknown.differsOnlyInEndPadding)
        assertFalse(compare(emptyList(), emptyList()).differsOnlyInEndPadding)
    }

    // ---- When a rewrite is tried ---------------------------------------------------------------

    private val matching = MediaTimelineEvidence.Result(true, 168, 168, 97_488, 97_488)

    @Test fun aRewriteIsTriedForTheEndPaddingAloneAndOnlyOnMedia3sOwnFile() {
        val paddingOnly = compare(aac(), aac())
        assertTrue(CandidateContainerRepair.endPaddingAlone(matching, paddingOnly, platformMuxed = false))
        // The platform's muxer already wrote this file: its padding is what that muxer writes.
        assertFalse(CandidateContainerRepair.endPaddingAlone(matching, paddingOnly, platformMuxed = true))
        // The video timeline has to match already: a rewrite restores no frame.
        val short = MediaTimelineEvidence.Result(false, 168, 166, 97_488, 0, reason = "frame count 168 -> 166")
        assertFalse(CandidateContainerRepair.endPaddingAlone(short, paddingOnly, platformMuxed = false))
        assertFalse(CandidateContainerRepair.endPaddingAlone(null, paddingOnly, platformMuxed = false))
        // Identical audio needs no rewrite, and any other difference is not one a rewrite puts right.
        assertFalse(CandidateContainerRepair.endPaddingAlone(matching, compare(aac(), aac(), outputConfig = config(padding = 0)), false))
        assertFalse(CandidateContainerRepair.endPaddingAlone(matching, compare(aac(), aac(change = 3)), false))
        assertFalse(CandidateContainerRepair.endPaddingAlone(matching, compare(aac(), aac(328)), false))
        assertFalse(CandidateContainerRepair.endPaddingAlone(matching, null, false))
    }

    @Test fun theFirstTimestampRepairStartsFromIdenticalAudioOrFromTheEndPaddingAlone() {
        val identical = compare(aac(), aac(), outputConfig = config(padding = 0))
        val paddingOnly = compare(aac(), aac())
        val basis = CandidateContainerRepair::audioBasis
        assertEquals(CandidateContainerRepair.AudioBasis.IDENTICAL, basis(identical, false))
        // As before this build: identical audio lets the repair go ahead whoever wrote the file.
        assertEquals(CandidateContainerRepair.AudioBasis.IDENTICAL, basis(identical, true))
        // The same rewrite puts the padding right too, on Media3's own file.
        assertEquals(CandidateContainerRepair.AudioBasis.END_PADDING, basis(paddingOnly, false))
        assertNull(basis(paddingOnly, true))
        // Everything else stops it, as before.
        assertNull(basis(compare(aac(), aac(change = 0)), false))
        assertNull(basis(compare(aac(), aac(shiftUs = 21_333)), false))
        assertNull(basis(compare(aac(), aac(), outputConfig = config(padding = 2, csd0 = null)), false))
        assertNull(basis(null, false))
    }

    @Test fun theRecordNamesWhatTheRewriteDid() {
        val identical = CandidateContainerRepair.AudioBasis.IDENTICAL
        val padding = CandidateContainerRepair.AudioBasis.END_PADDING
        // The name and the message earlier builds wrote for the first timestamp repair are kept.
        assertEquals("restore_first_video_timestamp", CandidateContainerRepair.operation(restoredFirstVideoTimestamp = true))
        assertEquals("Restored source video start time", CandidateContainerRepair.message(true, identical))
        assertEquals("restore_audio_end_padding", CandidateContainerRepair.operation(restoredFirstVideoTimestamp = false))
        assertEquals("Restored source audio end padding", CandidateContainerRepair.message(false, padding))
        assertEquals("Restored source video start time and audio end padding", CandidateContainerRepair.message(true, padding))
    }
}
