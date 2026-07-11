package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualitySafetyTest {
    @Test
    fun perceptuallyLossless4k60HdrKeepsConservativeBitrateFloor() {
        val source = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 60f,
            durationMs = 60_000,
            totalBitrate = 119_900_000,
            audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            audioMime = MimeTypes.AUDIO_AAC,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED
        )

        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265
        )

        assertTrue(target >= 95_000_000)
        assertTrue(target > 64_000_000)
    }

    @Test
    fun perceptuallyLosslessNeverCaps120FpsEvenWithExplicitFpsOption() {
        // Perceptually Lossless and Remux Only always preserve source FPS/motion timing,
        // no matter which FPS chip is selected.
        for (choice in BatchFrameRateChoice.entries) {
            assertEquals(
                null,
                BatchQualityBitratePolicy.plannedOutputFps(120f, BatchQualityMode.PERCEPTUAL_LOSSLESS, choice)
            )
            assertEquals(
                null,
                BatchQualityBitratePolicy.plannedOutputFps(120f, BatchQualityMode.REMUX_ONLY, choice)
            )
        }
        // Lossy modes honor an explicit user cap.
        assertEquals(
            60,
            BatchQualityBitratePolicy.plannedOutputFps(120f, BatchQualityMode.HIGH_QUALITY, BatchFrameRateChoice.FPS60)
        )
        assertEquals(
            30,
            BatchQualityBitratePolicy.plannedOutputFps(120f, BatchQualityMode.STORAGE_SAVER, BatchFrameRateChoice.FPS30)
        )
        assertEquals(
            null,
            BatchQualityBitratePolicy.plannedOutputFps(120f, BatchQualityMode.HIGH_QUALITY, BatchFrameRateChoice.SOURCE)
        )
    }

    @Test
    fun perceptualLossless120FpsFloorsAreStricterThan60FpsFloors() {
        fun source(fps: Float, hdr: Boolean) = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = fps,
            durationMs = 60_000,
            totalBitrate = 119_900_000,
            audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = if (hdr) MediaFormat.COLOR_TRANSFER_ST2084 else MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = if (hdr) MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709
        )

        val hdr120Floor = BatchQualityBitratePolicy.perceptualLosslessRatioFloor(source(120f, hdr = true))
        val hdr60Floor = BatchQualityBitratePolicy.perceptualLosslessRatioFloor(source(60f, hdr = true))
        val sdr120Floor = BatchQualityBitratePolicy.perceptualLosslessRatioFloor(source(120f, hdr = false))
        val sdr60Floor = BatchQualityBitratePolicy.perceptualLosslessRatioFloor(source(60f, hdr = false))

        assertTrue(hdr120Floor > hdr60Floor)
        assertTrue(sdr120Floor > sdr60Floor)
        assertTrue(hdr120Floor >= 0.90)
        assertTrue(hdr60Floor >= 0.80)
    }

    @Test
    fun perceptualLossless8kPreservesResolutionAndKeepsHighBitrateFloor() {
        val source = VideoSourceInfo(
            width = 7680,
            height = 4320,
            frameRate = 30f,
            durationMs = 60_000,
            totalBitrate = 200_000_000,
            audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )

        assertEquals(4320, BatchQualityBitratePolicy.targetHeightFor(4320, BatchQualityMode.PERCEPTUAL_LOSSLESS))

        val floor = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(source)
        assertTrue(floor >= 100_000_000)

        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265
        )
        assertTrue(target >= floor)
        assertTrue(target <= source.videoBitrate)
    }

    @Test
    fun learnedRatioCannotDropPerceptualLosslessBelowSafetyFloor() {
        val source = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 120f,
            durationMs = 60_000,
            totalBitrate = 119_900_000,
            audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )

        // Even a hostile learned ratio of 0.10 must be clamped to the HDR-120fps floor (0.90).
        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265,
            learnedTargetRatio = 0.10
        )
        val floor = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(source)
        assertTrue(target >= floor)
    }

    @Test
    fun nearOptimalSourcePrefersRemuxInsteadOfPointlessReencode() {
        val source = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 60f,
            durationMs = 60_000,
            totalBitrate = 119_900_000,
            audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )
        val sourceBytes = 119_900_000L * 60L / 8L

        // A target ratio pushed to the maximum cannot save >= 3%, so remux is the honest choice.
        assertTrue(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source = source,
                outputMimeType = MimeTypes.VIDEO_H265,
                sourceSizeBytes = sourceBytes,
                learnedTargetRatio = BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO
            )
        )

        // The default conservative ratio still predicts a useful saving, so the encode proceeds
        // and verification decides the truth.
        assertFalse(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source = source,
                outputMimeType = MimeTypes.VIDEO_H265,
                sourceSizeBytes = sourceBytes,
                learnedTargetRatio = null
            )
        )
    }

    @Test
    fun perceptualLosslessSizeEstimateIgnoresFpsChoice() {
        val source = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 120f,
            durationMs = 60_000,
            totalBitrate = 119_900_000,
            audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084
        )

        val estimateSourceFps = BatchQualityBitratePolicy.estimateOutputSize(
            source, BatchQualityMode.PERCEPTUAL_LOSSLESS, MimeTypes.VIDEO_H265, BatchFrameRateChoice.SOURCE
        )
        val estimateCappedFps = BatchQualityBitratePolicy.estimateOutputSize(
            source, BatchQualityMode.PERCEPTUAL_LOSSLESS, MimeTypes.VIDEO_H265, BatchFrameRateChoice.FPS30
        )

        assertEquals(estimateSourceFps, estimateCappedFps)
    }

    @Test
    fun audioDegradationAloneFailsPerceptualLosslessVerification() {
        val sourceTracks = OutputVerifier.TrackProbe(
            videoCodec = MimeTypes.VIDEO_H265,
            audioCodec = MimeTypes.AUDIO_AAC,
            videoBitrate = 119_000_000,
            audioBitrate = 320_000,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            audioChannelCount = 2,
            audioSampleRate = 48_000
        )
        // Video is fully preserved; only the audio bitrate degraded well below the source.
        val outputTracks = sourceTracks.copy(videoBitrate = 115_000_000, audioBitrate = 96_000)

        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                source = VideoSourceInfo(
                    width = 3840,
                    height = 2160,
                    frameRate = 60f,
                    durationMs = 60_000,
                    totalBitrate = 119_900_000,
                    audioBitrate = 320_000,
                    videoMime = MimeTypes.VIDEO_H265,
                    audioMime = MimeTypes.AUDIO_AAC,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 60f, 60_000, 90),
                sourceTrackProbe = sourceTracks,
                outputTrackProbe = outputTracks,
                sourceMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                outputMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                sourceSize = 900L * 1024L * 1024L,
                outputSize = 860L * 1024L * 1024L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertTrue(report.replacementBlockReason.orEmpty().contains("audio"))
    }

    // MediaMuxer outputs never expose per-track bitrate (no btrt box); verification must fall
    // back to bitrate measured from real output size/duration instead of failing forever.
    private fun hdr4k60VerificationInput(
        outputSize: Long,
        sourceSize: Long,
        outputDurationMs: Long
    ): OutputVerifier.VerificationInput {
        val sourceTracks = OutputVerifier.TrackProbe(
            videoCodec = MimeTypes.VIDEO_H265,
            audioCodec = MimeTypes.AUDIO_AAC,
            videoBitrate = 119_946_000,
            audioBitrate = 256_000,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            audioChannelCount = 2,
            audioSampleRate = 48_000
        )
        // Real S23 Ultra Transformer+MediaMuxer output: same codec/shape/HDR, bitrates hidden.
        val outputTracks = sourceTracks.copy(videoBitrate = 0, audioBitrate = 0, videoFrameRate = 59f)
        return OutputVerifier.VerificationInput(
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            source = VideoSourceInfo(
                width = 2160,
                height = 3840,
                frameRate = 60f,
                durationMs = outputDurationMs,
                totalBitrate = 120_202_307,
                audioBitrate = 256_000,
                videoMime = MimeTypes.VIDEO_H265,
                audioMime = MimeTypes.AUDIO_AAC,
                colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                audioChannelCount = 2,
                audioSampleRate = 48_000
            ),
            outputFileProbe = OutputVerifier.FileProbe(2160, 3840, 59f, outputDurationMs, 90),
            sourceTrackProbe = sourceTracks,
            outputTrackProbe = outputTracks,
            sourceMetadata = VideoMetadataSnapshot(rawDateTag = "tag", rotationDegrees = 90),
            outputMetadata = VideoMetadataSnapshot(rawDateTag = "tag", rotationDegrees = 90),
            sourceSize = sourceSize,
            outputSize = outputSize,
            privacyMode = MetadataPrivacyMode.PRESERVE_ALL
        )
    }

    @Test
    fun perceptualLosslessVerifiesWithMeasuredBitrateWhenContainerHidesIt() {
        // 93.247s output at ~1.19 GB measures to ~102 Mbps video — above the 95.9 Mbps floor —
        // and the file shrank, so this must be verifiable despite hidden container bitrates.
        val report = OutputVerifier.verify(
            hdr4k60VerificationInput(
                outputSize = 1_190_000_000L,
                sourceSize = 1_401_060_070L,
                outputDurationMs = 93_247L
            )
        )

        assertEquals("Perceptually Lossless Verified", report.verdict)
        assertTrue(report.verified)
        assertTrue(report.replacementSafe)
        assertTrue(report.videoBitrate.contains("measured from size"))
        assertTrue(report.audioBitrate.contains("stream copied"))
    }

    @Test
    fun perceptualLosslessSizeGrowthStillFailsWithMeasuredBitrate() {
        // The real device run: encoder overshoot grew 1.3 GB to 1.5 GB. With measured bitrates
        // the verdict must be a decisive size-tolerance failure, not "critical fields missing".
        val report = OutputVerifier.verify(
            hdr4k60VerificationInput(
                outputSize = 1_500_000_000L,
                sourceSize = 1_401_060_070L,
                outputDurationMs = 93_247L
            )
        )

        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertEquals("Perceptually Lossless Verification Failed", report.verdict)
        assertTrue(report.replacementBlockReason.orEmpty().contains("size growth tolerance"))
    }

    @Test
    fun remuxAcceptsHiddenOutputAudioBitrateWhenStreamShapeMatches() {
        val sourceTracks = OutputVerifier.TrackProbe(
            videoCodec = MimeTypes.VIDEO_H265,
            audioCodec = MimeTypes.AUDIO_AAC,
            videoBitrate = 79_500_000,
            audioBitrate = 256_000,
            colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            audioChannelCount = 2,
            audioSampleRate = 48_000
        )
        val outputTracks = sourceTracks.copy(videoBitrate = 0, audioBitrate = 0, videoFrameRate = 60f)

        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.REMUX_ONLY,
                source = VideoSourceInfo(
                    width = 3840,
                    height = 2160,
                    frameRate = 60f,
                    durationMs = 60_000,
                    totalBitrate = 80_000_000,
                    audioBitrate = 256_000,
                    videoMime = MimeTypes.VIDEO_H265,
                    audioMime = MimeTypes.AUDIO_AAC,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 60f, 60_000, 90),
                sourceTrackProbe = sourceTracks,
                outputTrackProbe = outputTracks,
                sourceMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                outputMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                sourceSize = 600_000_000L,
                // Stream copy: essentially the same size, so measured bitrate matches the source.
                outputSize = 599_999_000L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertEquals("Remux Verified", report.verdict)
        assertTrue(report.verified)
        assertTrue(report.replacementSafe)
    }

    @Test
    fun overshootAwarePredictionPrefersRemuxWhenEncoderOvershoots() {
        val source = VideoSourceInfo(
            width = 2160,
            height = 3840,
            frameRate = 60f,
            durationMs = 93_247,
            totalBitrate = 120_202_307,
            audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )
        val sourceBytes = 1_401_060_070L

        // Without overshoot knowledge, the default 0.95 target predicts a useful saving.
        assertFalse(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source, MimeTypes.VIDEO_H265, sourceBytes, learnedTargetRatio = null
            )
        )
        // With the measured S23 Ultra VBR overshoot (~1.25x), the same request predicts an output
        // LARGER than the source, so the honest pre-encode decision is remux — no wasted encode.
        assertTrue(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source, MimeTypes.VIDEO_H265, sourceBytes,
                learnedTargetRatio = null,
                expectedOvershootFactor = 1.25
            )
        )
        // A sub-1.0 (or corrupt) factor is clamped to 1.0 — it cannot fake extra headroom.
        val predictedClamped = BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
            source, MimeTypes.VIDEO_H265, null, expectedOvershootFactor = 0.10
        )
        val predictedBaseline = BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
            source, MimeTypes.VIDEO_H265, null
        )
        assertEquals(predictedBaseline, predictedClamped)
    }

    @Test
    fun truncatedOutputCannotPassPerceptualLosslessVerification() {
        // Codex-accepted finding: a 5s output of a 93s source is "strictly smaller" and could
        // previously pass every per-field check. Duration parity must fail it decisively.
        val input = hdr4k60VerificationInput(
            outputSize = 90_000_000L,
            sourceSize = 1_401_060_070L,
            outputDurationMs = 93_247L
        )
        val truncated = input.copy(
            outputFileProbe = input.outputFileProbe.copy(durationMs = 5_000L)
        )
        val report = OutputVerifier.verify(truncated)

        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertTrue(report.replacementBlockReason.orEmpty().contains("truncation"))
    }

    @Test
    fun frameCountMismatchFailsVerificationWhenExposed() {
        val input = hdr4k60VerificationInput(
            outputSize = 1_190_000_000L,
            sourceSize = 1_401_060_070L,
            outputDurationMs = 93_247L
        )
        // ~5595 source frames at 60fps; output lost 20% of them but kept duration/metadata.
        val dropped = input.copy(
            sourceFrameCount = 5_595,
            outputFileProbe = input.outputFileProbe.copy(frameCount = 4_476)
        )
        val report = OutputVerifier.verify(dropped)
        assertFalse(report.verified)
        assertTrue(report.replacementBlockReason.orEmpty().contains("frame count"))

        // Equal counts (within 1%) pass.
        val intact = input.copy(
            sourceFrameCount = 5_595,
            outputFileProbe = input.outputFileProbe.copy(frameCount = 5_594)
        )
        assertTrue(OutputVerifier.verify(intact).verified)
    }

    @Test
    fun vfrStreamCopyUsesSameMeasurementMethodForFps() {
        // Samsung Super Steady: retriever reports the nominal 60fps for the source while the
        // extractor measures ~57fps on both files. A byte-identical stream copy must verify.
        val sourceTracks = OutputVerifier.TrackProbe(
            videoCodec = MimeTypes.VIDEO_H265,
            audioCodec = MimeTypes.AUDIO_AAC,
            videoBitrate = 33_876_215,
            audioBitrate = 256_000,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            audioChannelCount = 2,
            audioSampleRate = 48_000,
            videoFrameRate = 57.2f
        )
        val outputTracks = sourceTracks.copy(videoBitrate = 0, audioBitrate = 0)

        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.REMUX_ONLY,
                source = VideoSourceInfo(
                    width = 1440,
                    height = 2560,
                    frameRate = 60f, // nominal capture rate from the retriever
                    durationMs = 12_080,
                    totalBitrate = 34_132_215,
                    audioBitrate = 256_000,
                    videoMime = MimeTypes.VIDEO_H265,
                    audioMime = MimeTypes.AUDIO_AAC,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                // The output probe carries the extractor-measured ~57fps (the entry point patches
                // it in when CAPTURE_FRAMERATE is absent); against the nominal source 60 this
                // failed before the same-method fix.
                outputFileProbe = OutputVerifier.FileProbe(1440, 2560, 57.2f, 12_080, 90),
                sourceTrackProbe = sourceTracks,
                outputTrackProbe = outputTracks,
                sourceMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                outputMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                sourceSize = 51_540_072L,
                outputSize = 51_262_031L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertEquals("Remux Verified", report.verdict)
        assertTrue(report.verified)
        assertTrue(report.replacementSafe)
    }

    @Test
    fun portraitQhdIsNotClassedAsFourK() {
        // Super Steady portrait 1440x2560@60: raw height (2560) exceeds 2160, but the clip is
        // QHD-class by long edge. It must get QHD floors, not the 48 Mbps 4K absolute floor that
        // forced target == source bitrate and a false "near optimal" remux preference.
        val source = VideoSourceInfo(
            width = 1440,
            height = 2560,
            frameRate = 60f,
            durationMs = 12_080,
            totalBitrate = 34_132_215,
            audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709
        )

        assertEquals(0.68, BatchQualityBitratePolicy.perceptualLosslessRatioFloor(source), 1e-9)

        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265
        )
        // Default HEVC->HEVC ratio 0.95 of ~33.9 Mbps video — no longer clamped up to source.
        assertTrue(target < source.videoBitrate)

        // And the pre-encode gate now predicts a real saving instead of preferring remux.
        assertFalse(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source, MimeTypes.VIDEO_H265, 51_540_072L,
                learnedTargetRatio = null,
                expectedOvershootFactor = 1.0
            )
        )

        // True 4K (landscape or portrait) keeps its 4K floors.
        val portrait4k = source.copy(width = 2160, height = 3840, totalBitrate = 120_202_307)
        assertEquals(0.74, BatchQualityBitratePolicy.perceptualLosslessRatioFloor(portrait4k), 1e-9)
    }

    @Test
    fun downloadedLowBitrate1080pH264NowGetsRealSubSourceTarget() {
        // The core downloaded-video bug: a 1080p H.264 web download at ~4 Mbps sits BELOW the
        // 10 Mbps camera-class absolute floor, so the old floor clamped the perceptually-lossless
        // target up to exactly the source bitrate -> predicted 0% savings -> silent remux for the
        // whole batch. With the floor bound only to at/above-floor sources, an H.264->HEVC transcode
        // now targets a real sub-source bitrate that verification can accept.
        val download = VideoSourceInfo(
            width = 1920,
            height = 1080,
            frameRate = 30f,
            durationMs = 60_000,
            totalBitrate = 4_000_000,
            audioBitrate = 128_000,
            videoMime = MimeTypes.VIDEO_H264,
            audioMime = MimeTypes.AUDIO_AAC,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709
        )

        val floor = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(download)
        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = download,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265
        )

        // Floor now reflects the ratio floor (0.55x), not the 10 Mbps camera absolute floor.
        assertTrue("floor should drop below source for a sub-floor download", floor < download.videoBitrate)
        // Target is a genuine sub-source bitrate (H.264->HEVC 0.90 default), not clamped to source.
        assertTrue("target must be below source video bitrate", target < download.videoBitrate)
        assertTrue("target must stay at or above the ratio floor", target >= floor)

        // The pre-encode near-optimal gate must now let the encode proceed instead of preferring
        // remux, so downloaded videos actually get compressed.
        assertFalse(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source = download,
                outputMimeType = MimeTypes.VIDEO_H265,
                sourceSizeBytes = 30_000_000L,
                learnedTargetRatio = null,
                expectedOvershootFactor = 1.0
            )
        )
    }

    @Test
    fun cameraClassFloorsAreByteIdenticalAfterTheDownloadFix() {
        // Regression guard: the floor change must ONLY affect sources below their absolute floor.
        // Every camera source (at or above the floor) must produce the exact same floor as before.
        // 1080p H.264 camera clip at 20 Mbps (>= 10 Mbps floor): floor = 20M*0.55 = 11M dominates.
        val camera1080p = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000,
            totalBitrate = 20_000_000, audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H264, colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        val floor1080 = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(camera1080p)
        // 20M - 256k = 19.744M video; ratio floor 0.55 -> 10.86M; abs floor 10M does not raise it.
        assertEquals(10_859_200, floor1080)

        // 4K60 HDR camera clip at ~120 Mbps: HDR-4K60 ratio floor 0.80 dominates the 48M abs floor.
        val camera4k = VideoSourceInfo(
            width = 3840, height = 2160, frameRate = 60f, durationMs = 60_000,
            totalBitrate = 119_900_000, audioBitrate = 320_000,
            videoMime = MimeTypes.VIDEO_H265,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084, colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )
        val floor4k = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(camera4k)
        val video4k = camera4k.videoBitrate // 119.9M - 320k = 119.58M
        assertEquals((video4k * 0.80).toInt(), floor4k)
        assertTrue("4K HDR floor must stay >= 95 Mbps", floor4k >= 95_000_000)
    }

    @Test
    fun cbrCapabilityProbeFailsClosedWithoutCrashing() {
        // On a platform where the codec list is unavailable (or CQ/CBR unsupported), the probe
        // must return false — the experiment silently stays on the safe VBR path, never crashes.
        assertFalse(ExperimentalEncoderControls.isCbrSupportedByHardwareEncoder(MimeTypes.VIDEO_H265))
    }

    @Test
    fun verifiedButNotSmallerOutputCannotReplaceOriginal() {
        // Within the +3% verification tolerance but 1 byte larger than the source: the verdict
        // stays honest ("Verified") but replacing the user's original must be blocked.
        val report = OutputVerifier.verify(
            hdr4k60VerificationInput(
                outputSize = 1_401_060_071L,
                sourceSize = 1_401_060_070L,
                outputDurationMs = 93_247L
            )
        )

        assertEquals("Perceptually Lossless Verified", report.verdict)
        assertTrue(report.verified)
        assertFalse(report.replacementSafe)
        assertTrue(report.replacementBlockReason.orEmpty().contains("not smaller"))
    }

    @Test
    fun experimentalEncoderCeilingFlagNeverBypassesVerificationInputs() {
        // The experiment only changes how an encode is REQUESTED. The verifier has no knowledge
        // of the flag: an oversized output fails identically with or without the experiment.
        val report = OutputVerifier.verify(
            hdr4k60VerificationInput(
                outputSize = 1_500_000_000L,
                sourceSize = 1_401_060_070L,
                outputDurationMs = 93_247L
            )
        )
        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
    }

    @Test
    fun outputFpsFallsBackToTrackFrameRateWhenRetrieverDoesNotExposeIt() {
        // The extractor-provided track frame rate keeps FPS verification working when
        // METADATA_KEY_CAPTURE_FRAMERATE is absent on the encoded output.
        val probe = OutputVerifier.TrackProbe(
            videoCodec = MimeTypes.VIDEO_H265,
            audioCodec = null,
            videoBitrate = 50_000_000,
            audioBitrate = 0,
            colorTransfer = null,
            colorStandard = null,
            colorRange = null,
            audioChannelCount = null,
            audioSampleRate = null,
            videoFrameRate = 119.88f
        )
        assertEquals(119.88f, probe.videoFrameRate, 0.001f)
        assertEquals(
            VerificationTransitionStatus.MATCH,
            OutputVerificationFormatter.fpsComparison(120f, probe.videoFrameRate)
        )
    }

    @Test
    fun s23LikeProfilePrefersHevcForHdrCameraSources() {
        val profile = DeviceCapabilityProfile(
            name = "Test S23",
            isGalaxyS23Ultra = true,
            preferHevcForDefaultCompression = true,
            avoidAv1EncodingByDefault = true,
            recommendedBatchParallelism = 1
        )
        val source = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 60f,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084
        )

        val chosen = profile.chooseDefaultVideoCodec(
            listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1),
            source
        )

        assertEquals(MimeTypes.VIDEO_H265, chosen)
    }

    @Test
    fun zeroDimensionsAreNotTreatedAsSafeMatch() {
        assertFalse(OutputVerifier.sameDimensions(3840, 2160, 0, 0))
    }

    @Test
    fun perceptuallyLosslessFailsVerificationOnHdrAndBitrateLoss() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                source = VideoSourceInfo(
                    width = 3840,
                    height = 2160,
                    frameRate = 120f,
                    durationMs = 60_000,
                    totalBitrate = 119_900_000,
                    audioBitrate = 256_000,
                    videoMime = MimeTypes.VIDEO_H265,
                    audioMime = MimeTypes.AUDIO_AAC,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 60f, 60_000, 90),
                sourceTrackProbe = OutputVerifier.TrackProbe(
                    videoCodec = MimeTypes.VIDEO_H265,
                    audioCodec = MimeTypes.AUDIO_AAC,
                    videoBitrate = 119_000_000,
                    audioBitrate = 256_000,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputTrackProbe = OutputVerifier.TrackProbe(
                    videoCodec = MimeTypes.VIDEO_H264,
                    audioCodec = MimeTypes.AUDIO_AAC,
                    videoBitrate = 64_000_000,
                    audioBitrate = 128_000,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                sourceMetadata = VideoMetadataSnapshot(
                    rawDateTag = "2026-07-08T00:00:00Z",
                    dateSource = "MediaStore date",
                    latitude = 1.0,
                    longitude = 2.0,
                    rotationDegrees = 90
                ),
                outputMetadata = VideoMetadataSnapshot(rotationDegrees = 90),
                sourceSize = 900L * 1024L * 1024L,
                outputSize = 700L * 1024L * 1024L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertFalse(report.verified)
        assertFalse(report.replacementSafe)
        assertTrue(report.verdict.contains("Failed") || report.verdict.contains("Unverified"))
    }

    @Test
    fun remuxOnlyRequiresCompleteVerificationBeforeReplacement() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.REMUX_ONLY,
                source = VideoSourceInfo(
                    width = 3840,
                    height = 2160,
                    frameRate = 60f,
                    durationMs = 60_000,
                    totalBitrate = 80_000_000,
                    audioBitrate = 256_000,
                    videoMime = MimeTypes.VIDEO_H265,
                    audioMime = MimeTypes.AUDIO_AAC,
                    colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                    audioChannelCount = 2,
                    audioSampleRate = 48_000
                ),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 60f, 60_000, 90),
                sourceTrackProbe = OutputVerifier.TrackProbe(
                    MimeTypes.VIDEO_H265,
                    MimeTypes.AUDIO_AAC,
                    79_500_000,
                    256_000,
                    MediaFormat.COLOR_TRANSFER_HLG,
                    MediaFormat.COLOR_STANDARD_BT2020,
                    MediaFormat.COLOR_RANGE_LIMITED,
                    2,
                    48_000
                ),
                outputTrackProbe = OutputVerifier.TrackProbe(
                    MimeTypes.VIDEO_H265,
                    MimeTypes.AUDIO_AAC,
                    79_000_000,
                    256_000,
                    MediaFormat.COLOR_TRANSFER_HLG,
                    MediaFormat.COLOR_STANDARD_BT2020,
                    MediaFormat.COLOR_RANGE_LIMITED,
                    2,
                    48_000
                ),
                sourceMetadata = VideoMetadataSnapshot(
                    rawDateTag = "2026-07-08T00:00:00Z",
                    dateSource = "MediaStore date",
                    latitude = 1.0,
                    longitude = 2.0,
                    rotationDegrees = 90
                ),
                outputMetadata = VideoMetadataSnapshot(
                    rawDateTag = "2026-07-08T00:00:00Z",
                    dateSource = "MediaStore date",
                    latitude = 1.0,
                    longitude = 2.0,
                    rotationDegrees = 90
                ),
                sourceSize = 500L * 1024L * 1024L,
                outputSize = 501L * 1024L * 1024L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertTrue(report.verified)
        assertTrue(report.replacementSafe)
        assertEquals("verified", report.mediaStoreDate)
        assertEquals("verified", report.mp4Date)
    }

    @Test
    fun missingCriticalFieldsMakePerceptualLosslessUnverified() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                source = VideoSourceInfo(width = 3840, height = 2160, frameRate = 60f, durationMs = 1_000, totalBitrate = 80_000_000, audioBitrate = 256_000),
                outputFileProbe = OutputVerifier.FileProbe(3840, 2160, 0f, 1_000, 0),
                sourceTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, 79_000_000, 256_000, null, null, null, 2, 48_000),
                outputTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC, 79_000_000, 256_000, null, null, null, null, null),
                sourceMetadata = VideoMetadataSnapshot(),
                outputMetadata = VideoMetadataSnapshot(),
                sourceSize = 100,
                outputSize = 100,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertEquals("Perceptually Lossless Unverified", report.verdict)
        assertFalse(report.replacementSafe)
    }

    @Test
    fun dateWordingSeparatesMediaStoreAndMp4Metadata() {
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.HIGH_QUALITY,
                source = VideoSourceInfo(width = 1920, height = 1080, frameRate = 30f, durationMs = 1_000, totalBitrate = 10_000_000, audioBitrate = 128_000),
                outputFileProbe = OutputVerifier.FileProbe(1920, 1080, 30f, 1_000, 0),
                sourceTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC, 9_000_000, 128_000, null, null, null, 2, 48_000),
                outputTrackProbe = OutputVerifier.TrackProbe(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC, 7_000_000, 128_000, null, null, null, 2, 48_000),
                sourceMetadata = VideoMetadataSnapshot(rawDateTag = "tag", dateSource = "MediaStore date"),
                outputMetadata = VideoMetadataSnapshot(rawDateTag = "tag"),
                sourceSize = 100,
                outputSize = 90,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )

        assertEquals("unverified", report.mediaStoreDate)
        assertEquals("verified", report.mp4Date)
    }

    @Test
    fun perceptualLosslessFallbackTriggersWhenVerificationIsUnverified() {
        val report = OutputVerificationReport(
            verdict = "Perceptually Lossless Unverified",
            playability = "opens",
            video = "",
            fps = "",
            videoBitrate = "",
            videoCodec = "",
            audioCodec = "",
            audioDetails = "",
            audioBitrate = "",
            hdr = "",
            colorStandard = "",
            colorRange = "",
            mediaStoreDate = "unverified",
            mp4Date = "verified",
            location = "unverified",
            rotation = "",
            fileSize = "",
            replacementSafe = false,
            replacementBlockReason = "critical fields missing",
            criticalFieldsComplete = false,
            verified = false
        )

        assertTrue(PerceptualLosslessVerifier.shouldFallbackToRemux(report, 100L, 100L))
    }
}
