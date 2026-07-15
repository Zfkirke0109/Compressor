package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // High-bit-density SDR H.264 camera clip -> HEVC: the only class the 2026-07-14 VMAF
        // evidence still allows a PL re-encode for.
        val source = VideoSourceInfo(
            width = 1920,
            height = 1080,
            frameRate = 30f,
            durationMs = 60_000,
            totalBitrate = 20_000_000,
            audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H264,
            audioMime = MimeTypes.AUDIO_AAC,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709
        )
        val sourceBytes = 20_000_000L * 60L / 8L

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
    fun hdrSourceAlwaysPrefersRemuxForPerceptualLossless() {
        // Zero HDR pairs exist in the pixel-validation evidence; a stream copy is the only output
        // proven to preserve HDR/color exactly, so PL must never re-encode an HDR source.
        val hdrH264 = VideoSourceInfo(
            width = 3840,
            height = 2160,
            frameRate = 60f,
            durationMs = 60_000,
            totalBitrate = 120_000_000,
            audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H264,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )
        assertTrue(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source = hdrH264,
                outputMimeType = MimeTypes.VIDEO_H265,
                sourceSizeBytes = 900_000_000L,
                learnedTargetRatio = null
            )
        )
    }

    @Test
    fun lowBitDensitySourcePrefersRemuxForPerceptualLossless() {
        // VMAF-calibrated transparency gate: every measured pair below ~0.08 bits/pixel/frame
        // failed the perceptual thresholds (4K60 at bpp 0.018 scored 1%-low 60.7 even at ratio
        // 0.90). Sources below the gate must keep the exact stream copy.
        val starved4k60 = VideoSourceInfo(
            width = 2160,
            height = 3840,
            frameRate = 60f,
            durationMs = 88_816,
            totalBitrate = 8_964_225,
            audioBitrate = 128_040,
            videoMime = MimeTypes.VIDEO_H264,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        assertTrue(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source = starved4k60,
                outputMimeType = MimeTypes.VIDEO_H265,
                sourceSizeBytes = 99_521_194L,
                learnedTargetRatio = null
            )
        )
        assertFalse(BatchQualityBitratePolicy.sourceSupportsTransparentPerceptualLossless(starved4k60))

        // Unknown source bitrate fails closed: transparency headroom cannot be proven.
        val unknownBitrate = starved4k60.copy(totalBitrate = 0)
        assertFalse(BatchQualityBitratePolicy.sourceSupportsTransparentPerceptualLossless(unknownBitrate))

        // A healthy-density camera clip passes the gate.
        val healthy1080p = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000,
            totalBitrate = 20_000_000, audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H264, colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        assertTrue(BatchQualityBitratePolicy.sourceSupportsTransparentPerceptualLossless(healthy1080p))
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
            audioSampleRate = 48_000,
            // A real HDR HEVC output exposes its Main10 profile through the extractor, proving the
            // 10-bit path survived. The bitrate stays hidden (no btrt), but bit depth must be proven.
            videoProfile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        )
        // Real S23 Ultra Transformer+MediaMuxer output: same codec/shape/HDR/profile, bitrates hidden.
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
            audioSampleRate = 48_000,
            // HLG source requires 10-bit; a stream copy carries the source's Main10 profile through.
            videoProfile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
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
        // SDR H.264 -> HEVC with healthy bit density: the class PL may still re-encode.
        val source = VideoSourceInfo(
            width = 1920,
            height = 1080,
            frameRate = 30f,
            durationMs = 93_247,
            totalBitrate = 20_000_000,
            audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H264,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709
        )
        val sourceBytes = 20_000_000L / 8L * 93L

        // Without overshoot knowledge, the default 0.90 target predicts a useful saving.
        assertFalse(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source, MimeTypes.VIDEO_H265, sourceBytes, learnedTargetRatio = null
            )
        )
        // With a measured ~1.25x VBR overshoot, the same request predicts an output LARGER than
        // the source, so the honest pre-encode decision is remux — no wasted encode.
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
    fun tenBitHdrSourceFailsClosedWhenOutputIsNotProvenTenBit() {
        val base = hdr4k60VerificationInput(
            outputSize = 1_190_000_000L,
            sourceSize = 1_401_060_070L,
            outputDurationMs = 93_247L
        )
        // 8-bit Main output carrying copied PQ/BT2020 color tags: color labels must NOT substitute
        // for bit-depth proof. This is the exact "10-bit verifies as 8-bit" degradation to reject.
        val eightBit = base.copy(
            outputTrackProbe = base.outputTrackProbe.copy(
                videoProfile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            )
        )
        val eightBitReport = OutputVerifier.verify(eightBit)
        assertFalse(eightBitReport.verified)
        assertFalse(eightBitReport.replacementSafe)
        assertTrue(eightBitReport.replacementBlockReason.orEmpty().contains("HDR/color"))

        // Unknown output bit depth (profile not exposed) for a proven-10-bit source fails closed too.
        val unknownDepth = base.copy(
            outputTrackProbe = base.outputTrackProbe.copy(videoProfile = null)
        )
        val unknownReport = OutputVerifier.verify(unknownDepth)
        assertFalse(unknownReport.verified)
        assertFalse(unknownReport.replacementSafe)
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

        assertEquals(0.85, BatchQualityBitratePolicy.perceptualLosslessRatioFloor(source), 1e-9)

        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265
        )
        // Default HEVC->HEVC ratio 0.95 of ~33.9 Mbps video — no longer clamped up to source.
        assertTrue(target < source.videoBitrate)

        // Same-codec PL re-encodes now prefer the exact stream copy (2026-07-14 VMAF evidence:
        // HEVC->HEVC at ratio 0.95 scored 1%-low 79.8 despite a 97.1 mean).
        assertTrue(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source, MimeTypes.VIDEO_H265, 51_540_072L,
                learnedTargetRatio = null,
                expectedOvershootFactor = 1.0
            )
        )

        // True 4K (landscape or portrait) keeps 4K-class floors.
        val portrait4k = source.copy(width = 2160, height = 3840, totalBitrate = 120_202_307)
        assertEquals(0.85, BatchQualityBitratePolicy.perceptualLosslessRatioFloor(portrait4k), 1e-9)
    }

    @Test
    fun downloadedLowBitrate1080pH264PrefersRemuxAfterVmafEvidence() {
        // History: the pre-fix build silently remuxed low-bitrate downloads because a camera-class
        // absolute floor clamped their target up to the source bitrate; the "downloaded-video fix"
        // then allowed real sub-source targets. The 2026-07-14 VMAF suite measured exactly those
        // encodes and DISPROVED them: 1080p at bpp 0.050 / ratio 0.82 scored 1%-low 78.7, and every
        // low-density pair failed. A ~4 Mbps 1080p30 download sits at bpp ~0.062 — below the 0.08
        // transparency gate — so the honest PL action is again the exact stream copy, this time as
        // an explicit, evidence-based decision rather than a floor accident.
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

        assertFalse(BatchQualityBitratePolicy.sourceSupportsTransparentPerceptualLossless(download))
        assertTrue(
            BatchQualityBitratePolicy.shouldPreferRemuxForPerceptualLossless(
                source = download,
                outputMimeType = MimeTypes.VIDEO_H265,
                sourceSizeBytes = 30_000_000L,
                learnedTargetRatio = null,
                expectedOvershootFactor = 1.0
            )
        )

        // The bitrate math itself stays sane for callers that still compute it: the raised 0.85
        // ratio floor governs (the 10 Mbps camera absolute floor does not bind below itself).
        val floor = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(download)
        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = download,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = MimeTypes.VIDEO_H265
        )
        assertEquals((download.videoBitrate * 0.85).toInt(), floor)
        assertTrue("target must be below source video bitrate", target < download.videoBitrate)
        assertTrue("target must stay at or above the ratio floor", target >= floor)
    }

    @Test
    fun cameraClassFloorsFollowTheRaisedSdrRatioFloor() {
        // After the 2026-07-14 VMAF evidence the SDR ratio floor is 0.85; camera-class sources'
        // floors are governed by it whenever it exceeds the absolute floor.
        // 1080p H.264 camera clip at 20 Mbps: floor = 19.744M * 0.85 = 16.78M dominates the 10M abs.
        val camera1080p = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000,
            totalBitrate = 20_000_000, audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H264, colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        val floor1080 = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(camera1080p)
        assertEquals((camera1080p.videoBitrate * 0.85).toInt(), floor1080)

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
    fun crossCodecFloorScaleOnlyRelaxesTheAbsoluteFloorNeverTheRatioFloor() {
        // A 720p H.264 clip at ~8 Mbps: the cross-codec scale (0.65) still lowers the 6 Mbps
        // absolute-floor COMPONENT for an HEVC output, but with the evidence-raised 0.85 ratio
        // floor the ratio floor dominates both floors — the perceptual guard is never scaled.
        val h264_720p = VideoSourceInfo(
            width = 1280, height = 720, frameRate = 30f, durationMs = 81_000,
            totalBitrate = 8_168_020, audioBitrate = 168_505,
            videoMime = MimeTypes.VIDEO_H264, audioMime = MimeTypes.AUDIO_AAC,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        val legacyFloor = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(h264_720p)
        val hevcFloor = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(h264_720p, MimeTypes.VIDEO_H265)

        assertEquals((h264_720p.videoBitrate * 0.85).toInt(), legacyFloor)
        assertEquals(legacyFloor, hevcFloor)
        assertEquals(0.65, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(h264_720p, MimeTypes.VIDEO_H265), 1e-9)
    }

    @Test
    fun crossCodecFloorIsByteIdenticalForSameCodecAndForLegacyCallers() {
        // Same-codec (HEVC -> HEVC) and the legacy 1-arg call must never change: the scale is 1.0.
        val hevc1080p = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f, durationMs = 60_000,
            totalBitrate = 20_000_000, audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H265, colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        val legacy = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(hevc1080p)
        val sameCodec = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(hevc1080p, MimeTypes.VIDEO_H265)
        assertEquals(legacy, sameCodec)
    }

    @Test
    fun crossCodecFloorNeverScalesHdrSources() {
        // Belt-and-suspenders: even a (contrived) HDR source being sent to a more-efficient codec must
        // keep its full absolute floor — HDR floors are never relaxed by the codec-efficiency scale.
        val hdr4kH264 = VideoSourceInfo(
            width = 3840, height = 2160, frameRate = 30f, durationMs = 60_000,
            totalBitrate = 80_000_000, audioBitrate = 256_000,
            videoMime = MimeTypes.VIDEO_H264,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084, colorStandard = MediaFormat.COLOR_STANDARD_BT2020
        )
        val legacy = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(hdr4kH264)
        val toHevc = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(hdr4kH264, MimeTypes.VIDEO_H265)
        assertEquals(legacy, toHevc)
        assertEquals(1.0, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(hdr4kH264, MimeTypes.VIDEO_H265), 1e-9)
    }

    @Test
    fun crossCodecAbsoluteFloorScaleOnlyRelaxesForStrictlyMoreEfficientSdrOutput() {
        fun sdr(mime: String) = VideoSourceInfo(
            width = 1920, height = 1080, frameRate = 30f,
            videoMime = mime, colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
        )
        // H.264 -> HEVC/VP9: one tier of headroom -> 0.65.
        assertEquals(0.65, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(sdr(MimeTypes.VIDEO_H264), MimeTypes.VIDEO_H265), 1e-9)
        // H.264 -> AV1: two tiers -> 0.50.
        assertEquals(0.50, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(sdr(MimeTypes.VIDEO_H264), MimeTypes.VIDEO_AV1), 1e-9)
        // Same codec, less-efficient output, unknown codec, and null output all stay 1.0.
        assertEquals(1.0, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(sdr(MimeTypes.VIDEO_H265), MimeTypes.VIDEO_H265), 1e-9)
        assertEquals(1.0, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(sdr(MimeTypes.VIDEO_H265), MimeTypes.VIDEO_H264), 1e-9)
        assertEquals(1.0, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(sdr("video/unknown"), MimeTypes.VIDEO_H265), 1e-9)
        assertEquals(1.0, BatchQualityBitratePolicy.crossCodecAbsoluteFloorScale(sdr(MimeTypes.VIDEO_H264), null), 1e-9)
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
    fun perceptualLosslessNeverDowngradesAnEfficientSourceCodecForNominalSavings() {
        assertTrue(
            BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
                MimeTypes.VIDEO_AV1,
                MimeTypes.VIDEO_H265
            )
        )
        assertTrue(
            BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
                MimeTypes.VIDEO_H265,
                MimeTypes.VIDEO_H264
            )
        )
        assertTrue(
            BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
                null,
                MimeTypes.VIDEO_H265
            )
        )
        assertFalse(
            BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
                MimeTypes.VIDEO_H264,
                MimeTypes.VIDEO_H265
            )
        )
        // Same-codec re-encode is preserved (remuxed) since the 2026-07-14 VMAF evidence:
        // HEVC->HEVC at ratio 0.95 failed the 1%-low threshold (79.8 vs 90).
        assertTrue(
            BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
                MimeTypes.VIDEO_H265,
                MimeTypes.VIDEO_H265
            )
        )
        assertTrue(
            BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
                MimeTypes.VIDEO_VP9,
                MimeTypes.VIDEO_H265
            )
        )
        assertTrue(
            BatchQualityBitratePolicy.shouldPreserveSourceCodecForPerceptualLossless(
                MimeTypes.VIDEO_DOLBY_VISION,
                MimeTypes.VIDEO_H265
            )
        )
    }

    @Test
    fun portraitTransformerRotationPreservesDisplayGeometryWithoutWeakeningResolution() {
        // The probes normalize each file's own rotation before comparing dimensions. Raw coded
        // transposition never passes the dimension gate by itself.
        assertTrue(OutputVerifier.sameDimensions(2160, 3840, 2160, 3840))
        assertFalse(OutputVerifier.sameDimensions(2160, 3840, 3840, 2160))
        assertFalse(OutputVerifier.sameDimensions(2160, 3840, 3840, 2161))
        assertFalse(OutputVerifier.sameDimensions(2160, 3840, 1920, 1080))
        assertFalse(OutputVerifier.sameDimensions(2160, 3840, 0, 2160))

        // Media3's valid portrait representation may change the raw rotation hint by a quarter
        // turn once normalized display dimensions match exactly.
        assertTrue(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                2160,
                3840,
                displayDimensionsMatch = true,
                sourceRotationDegrees = 0,
                outputRotationDegrees = 90
            )
        )
        // Remuxes must retain raw orientation, and transcodes never accept 180°, landscape
        // quarter-turns, or mismatched display geometry. An absent hint is canonical zero.
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.REMUX_ONLY, 2160, 3840, true, 0, 90
            )
        )
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, true, 0, 180
            )
        )
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 3840, 2160, true, 0, 90
            )
        )
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, false, 0, 90
            )
        )
        assertTrue(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, true, 0, null
            )
        )
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, true, null, 180
            )
        )
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, true, null, 45
            )
        )
        // A rotated source (raw 90/270) is only preserved by an exactly-equal output hint. The
        // portrait compensation exception is for coded-portrait sources (raw 0) ONLY: a 90->0 change
        // plays sideways and 90->180 plays upside down, so both must fail even though the normalized
        // display dimensions match. Equal hints (90->90, 270->270) always pass.
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, true, 90, 0
            )
        )
        assertFalse(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, true, 90, 180
            )
        )
        assertTrue(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.PERCEPTUAL_LOSSLESS, 2160, 3840, true, 90, 90
            )
        )
        assertTrue(
            OutputVerifier.sameDisplayOrientation(
                BatchQualityMode.REMUX_ONLY, 2160, 3840, true, 270, 270
            )
        )
    }

    @Test
    fun metadataRemuxKeepsEncodedOrientationInsteadOfGuessingFromSource() {
        assertEquals(90, Mp4MetadataRemuxer.orientationHintForTranscodedRemux(90))
        assertEquals(270, Mp4MetadataRemuxer.orientationHintForTranscodedRemux(270))
        assertNull(Mp4MetadataRemuxer.orientationHintForTranscodedRemux(null))
        assertNull(Mp4MetadataRemuxer.orientationHintForTranscodedRemux(45))
    }

    @Test
    fun baselinePortraitTranscodeVerifiesWithMedia3CompensatingRotation() {
        val sourceTracks = OutputVerifier.TrackProbe(
            videoCodec = MimeTypes.VIDEO_H264,
            audioCodec = MimeTypes.AUDIO_AAC,
            videoBitrate = 62_011_000,
            audioBitrate = 142_728,
            colorTransfer = null,
            colorStandard = null,
            colorRange = null,
            audioChannelCount = 2,
            audioSampleRate = 44_100,
            videoFrameRate = 60f
        )
        val outputTracks = sourceTracks.copy(
            videoCodec = MimeTypes.VIDEO_H265,
            videoBitrate = 0,
            audioBitrate = 0,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED
        )
        val report = OutputVerifier.verify(
            OutputVerifier.VerificationInput(
                mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                source = VideoSourceInfo(
                    width = 2160,
                    height = 3840,
                    frameRate = 60f,
                    durationMs = 88_817,
                    totalBitrate = 62_089_257,
                    audioBitrate = 142_728,
                    videoMime = MimeTypes.VIDEO_H264,
                    audioMime = MimeTypes.AUDIO_AAC,
                    rotationDegrees = 0,
                    audioChannelCount = 2,
                    audioSampleRate = 44_100
                ),
                // FileProbe dimensions are already display-normalized from coded 3840x2160 + 90°.
                outputFileProbe = OutputVerifier.FileProbe(2160, 3840, 60f, 88_863, 90, 5_323),
                sourceTrackProbe = sourceTracks,
                outputTrackProbe = outputTracks,
                sourceMetadata = VideoMetadataSnapshot(rawDateTag = "tag", rotationDegrees = 0),
                outputMetadata = VideoMetadataSnapshot(rawDateTag = "tag", rotationDegrees = 90),
                sourceSize = 689_308_985L,
                outputSize = 607_807_388L,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL,
                sourceFrameCount = 5_323
            )
        )

        assertEquals("Perceptually Lossless Verified", report.verdict)
        assertTrue(report.verified)
        assertTrue(report.replacementSafe)
        assertTrue(report.video.endsWith("ok"))
        assertTrue(report.rotation.endsWith("ok"))
        assertTrue(report.hdr.contains("Media3 assumed SDR default"))
    }

    @Test
    fun media3AssumedSdrTransitionIsNarrowAndHdrSafe() {
        fun probe(
            transfer: Int? = null,
            standard: Int? = null,
            range: Int? = null,
            hdrStaticDigest: String? = null,
            hdr10PlusDigest: String? = null,
            profile: Int? = null,
            mime: String = MimeTypes.VIDEO_H264
        ) = OutputVerifier.TrackProbe(
            videoCodec = mime,
            audioCodec = null,
            videoBitrate = 1_000_000,
            audioBitrate = 0,
            colorTransfer = transfer,
            colorStandard = standard,
            colorRange = range,
            audioChannelCount = null,
            audioSampleRate = null,
            hdrStaticInfoDigest = hdrStaticDigest,
            hdr10PlusInfoDigest = hdr10PlusDigest,
            videoProfile = profile
        )

        val absent = probe()
        val canonicalSdr = probe(
            transfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            standard = MediaFormat.COLOR_STANDARD_BT709,
            range = MediaFormat.COLOR_RANGE_LIMITED
        )
        val assumed = OutputVerifier.compareColorTransition(
            BatchQualityMode.PERCEPTUAL_LOSSLESS,
            absent,
            canonicalSdr
        )
        assertTrue(assumed.matches)
        assertEquals(OutputVerifier.ColorMatchBasis.MEDIA3_ASSUMED_SDR, assumed.basis)

        // A stream copy may not rewrite absent metadata, and an absent field is never a wildcard.
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.REMUX_ONLY,
                absent,
                canonicalSdr
            ).matches
        )
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                absent,
                canonicalSdr.copy(colorRange = MediaFormat.COLOR_RANGE_FULL)
            ).matches
        )
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                absent,
                canonicalSdr.copy(
                    colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                    colorStandard = MediaFormat.COLOR_STANDARD_BT2020
                )
            ).matches
        )

        // Static HDR metadata or a 10-bit-capable source profile disables the assumed-SDR path.
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                absent.copy(hdrStaticInfoDigest = "static-a"),
                canonicalSdr
            ).matches
        )
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                absent.copy(
                    videoCodec = MimeTypes.VIDEO_H265,
                    videoProfile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                ),
                canonicalSdr.copy(videoCodec = MimeTypes.VIDEO_H265)
            ).matches
        )

        val hdr = probe(
            transfer = MediaFormat.COLOR_TRANSFER_ST2084,
            standard = MediaFormat.COLOR_STANDARD_BT2020,
            range = MediaFormat.COLOR_RANGE_LIMITED,
            hdrStaticDigest = "static-a",
            profile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            mime = MimeTypes.VIDEO_H265
        )
        assertTrue(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                hdr,
                hdr
            ).matches
        )
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                hdr,
                canonicalSdr.copy(videoCodec = MimeTypes.VIDEO_H265)
            ).matches
        )
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                hdr,
                hdr.copy(hdrStaticInfoDigest = "static-b")
            ).matches
        )
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                hdr.copy(hdr10PlusInfoDigest = "dynamic-a"),
                hdr.copy(hdr10PlusInfoDigest = "dynamic-a")
            ).matches
        )
        assertFalse(
            OutputVerifier.compareColorTransition(
                BatchQualityMode.PERCEPTUAL_LOSSLESS,
                hdr,
                hdr.copy(videoProfile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            ).matches
        )
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
                    48_000,
                    videoProfile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
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
                    48_000,
                    videoProfile = android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
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
