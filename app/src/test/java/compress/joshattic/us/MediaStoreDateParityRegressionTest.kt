package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Output verification must never reject a file for a property the file cannot possibly have.
 *
 * `mediaStoreDateMatches` used to require the OUTPUT to expose a `dateSource` beginning with
 * "MediaStore". Verification runs on the pre-insertion cache file via `Uri.fromFile(outputFile)`,
 * and `VideoMetadataSnapshot.capture` reads that provenance from a `ContentResolver.query` — which
 * a `file://` URI has no provider to answer. The predicate was therefore unsatisfiable for every
 * generated output whose source came from the gallery, which is every real job.
 *
 * The field signature this produced is distinctive and is reproduced below: `verified = false`
 * with `replacementBlockReason = null`. The blockReason chain in [OutputVerifier] has no arm for
 * the date, location or audio-shape predicates, so those four are the only ones that can fail
 * silently. An S23 Ultra capture (batch_1786651683689) rejected 16 of 16 generated outputs with
 * exactly that signature, two of them pixel-proven perceptually lossless at ratio 0.65.
 *
 * These tests fix the shape of the bug so it cannot come back, and pin the narrower guarantee that
 * replaced it: the source date must have been captured and the output must still carry a date.
 */
class MediaStoreDateParityRegressionTest {

    // Modelled on job 73d4c308a8f8 of batch_1786651683689: 1920x1080 AVC, 30fps, no audio track,
    // ~10s, encoded to HEVC and then discarded.
    private val source = VideoSourceInfo(
        width = 1920,
        height = 1080,
        frameRate = 30f,
        durationMs = 10_011,
        totalBitrate = 25_139_226,
        audioBitrate = 0,
        videoMime = MimeTypes.VIDEO_H264,
        audioMime = null,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709
    )

    private val sourceTracks = OutputVerifier.TrackProbe(
        videoCodec = MimeTypes.VIDEO_H264,
        audioCodec = null,
        videoBitrate = 25_139_226,
        audioBitrate = 0,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED,
        audioChannelCount = null,
        audioSampleRate = null,
        videoFrameRate = 30f
    )

    /**
     * What `VideoMetadataSnapshot.capture` returns for a gallery `content://` source: the provider
     * answered, so the date provenance is a MediaStore column.
     */
    private val galleryDate = VideoMetadataSnapshot(
        dateTakenMs = 1_786_600_000_000L,
        dateModifiedSeconds = 1_786_600_000L,
        rawDateTag = "20260813T150121.000Z",
        dateSource = "MediaStore date",
        rotationDegrees = 0
    )

    /**
     * What it returns for the `file://` cache output: no provider row exists, so the only date is
     * the one the muxer wrote into the file, and its provenance is the retriever.
     */
    private val cacheFileDate = VideoMetadataSnapshot(
        dateTakenMs = 1_786_655_157_000L,
        rawDateTag = "20260813T162557.000Z",
        dateSource = "MP4/retriever date",
        rotationDegrees = 0
    )

    private fun input(
        mode: BatchQualityMode,
        outputMetadata: VideoMetadataSnapshot,
        outputVideoBitrate: Int,
        outputVideoCodec: String,
        outputSize: Long
    ) = OutputVerifier.VerificationInput(
        mode = mode,
        source = source,
        outputFileProbe = OutputVerifier.FileProbe(1920, 1080, 30f, 10_011, 0),
        sourceTrackProbe = sourceTracks,
        outputTrackProbe = sourceTracks.copy(
            videoCodec = outputVideoCodec,
            videoBitrate = outputVideoBitrate
        ),
        sourceMetadata = galleryDate,
        outputMetadata = outputMetadata,
        sourceSize = 31_458_599L,
        outputSize = outputSize,
        privacyMode = MetadataPrivacyMode.PRESERVE_ALL,
        // The encode was justified by on-device probes at 0.65, so the structural floor is the
        // pixel-proven one — otherwise the bitrate check, not the date check, would be the failure
        // under test.
        pixelProvenVideoBitrateFloor = ((0.65 - 0.06) * source.videoBitrate).toInt()
    )

    private fun perceptuallyLosslessEncode(outputMetadata: VideoMetadataSnapshot) = input(
        mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
        outputMetadata = outputMetadata,
        outputVideoBitrate = 15_747_890,
        outputVideoCodec = MimeTypes.VIDEO_H265,
        outputSize = 20_500_000L
    )

    private fun streamCopyRemux(outputMetadata: VideoMetadataSnapshot) = input(
        mode = BatchQualityMode.REMUX_ONLY,
        outputMetadata = outputMetadata,
        outputVideoBitrate = 25_139_226,
        outputVideoCodec = MimeTypes.VIDEO_H264,
        outputSize = 31_458_544L
    )

    @Test
    fun aPerceptuallyLosslessEncodeFromAGallerySourceIsNotRejectedForTheOutputHavingNoMediaStoreRow() {
        val report = OutputVerifier.verify(perceptuallyLosslessEncode(cacheFileDate))
        assertEquals(emptyList<String>(), report.failedChecks)
        assertTrue(report.verified)
    }

    @Test
    fun aByteIdenticalStreamCopyFromAGallerySourceIsNotRejectedEither() {
        // A remux copies the source packets verbatim. If verification can fail this, it can fail
        // anything, and the whole fallback chain terminates in OUTPUT_VALIDATION_FAILED.
        val report = OutputVerifier.verify(streamCopyRemux(cacheFileDate))
        assertEquals(emptyList<String>(), report.failedChecks)
        assertTrue(report.verified)
    }

    @Test
    fun losingTheDateEntirelyIsStillAHardFailure() {
        // The narrower guarantee has to keep real date loss out. An output carrying no date at all
        // fails, and names itself in failedChecks.
        val dateless = VideoMetadataSnapshot(rotationDegrees = 0)
        for (input in listOf(perceptuallyLosslessEncode(dateless), streamCopyRemux(dateless))) {
            val report = OutputVerifier.verify(input)
            assertFalse(report.verified)
            assertTrue("mediaStoreDateMatches" in report.failingChecks())
            assertTrue("mp4DateMatches" in report.failingChecks())
        }
    }

    @Test
    fun anOutputThatIsItselfAMediaStoreItemIsStillHeldToTheStrictComparison() {
        val inserted = cacheFileDate.copy(dateSource = "MediaStore date")
        val report = OutputVerifier.verify(perceptuallyLosslessEncode(inserted))
        assertEquals(emptyList<String>(), report.failedChecks)
        assertTrue(report.verified)
    }

    @Test
    fun aPassingEncodeNamesNoFailingCheckEvenThoughTheCodecDeliberatelyChanged() {
        // Device-observed: the first two outputs ever to pass Perceptually Lossless were recorded
        // as failedChecks ["videoCodec"] while simultaneously verified, pixel-certified and
        // replacement-safe. An H.264 -> HEVC change is the POINT of a PL encode and is outside the
        // PL scope; it only surfaced because an empty authoritative list was mistaken for a
        // missing one and the text-scanning fallback, which knows nothing of scopes, ran anyway.
        val report = OutputVerifier.verify(perceptuallyLosslessEncode(cacheFileDate))
        assertTrue(report.verified)
        assertEquals(emptyList<String>(), report.failedChecks)
        assertEquals(emptyList<String>(), report.failingChecks())
        assertTrue("videoCodec" !in report.failingChecks())
    }

    @Test
    fun theSilentFailureSignatureIsReproducibleAndNowHasANamedCause() {
        // This is the exact device signature: rejected, nothing in blockReason. It must never again
        // be reachable without failedChecks naming the predicate responsible, because a verdict
        // that identifies nothing is undiagnosable from a capture.
        val dateless = VideoMetadataSnapshot(rotationDegrees = 0)
        val report = OutputVerifier.verify(streamCopyRemux(dateless))
        assertFalse(report.verified)
        assertNull(report.replacementBlockReason)
        assertTrue(report.failingChecks().isNotEmpty())
        assertEquals(report.failedChecks, report.failingChecks())
    }
}
