package compress.joshattic.us

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaFormat
import androidx.media3.common.MimeTypes

/**
 * Local-only adaptive encoder calibration for Smart Perceptually Lossless.
 *
 * The engine remembers, per device + source profile, which target bitrate ratios produced
 * verified perceptually-lossless output and which failed verification. On later videos with a
 * similar profile it recommends a target: cautiously lower after repeated verified successes,
 * higher after failures, and "prefer remux" after repeated failures near the maximum ratio.
 *
 * Hard rules:
 *  - Everything stays on-device (SharedPreferences); nothing is uploaded and no network is used.
 *  - Only non-private technical values are stored (buckets, ratios, counts, failure reasons).
 *  - Recommendations are always clamped to at least max(safety floor, codec default ratio), so
 *    learning can never go below the HDR/120fps/high-bitrate safety floors NOR below the
 *    codec-appropriate default (structural-only feedback proved perceptually blind in the
 *    2026-07-14 VMAF suite).
 *  - Learning only picks the target; [OutputVerifier] verification always decides truth, and a
 *    learned setting can never bypass or relax verification.
 */
class SmartPerceptualProfileEngine(private val store: ProfileStore) {

    /** Minimal key/value storage so the learning core is unit-testable without Android. */
    interface ProfileStore {
        fun read(key: String): String?
        fun write(key: String, value: String)
    }

    class InMemoryProfileStore : ProfileStore {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun write(key: String, value: String) {
            values[key] = value
        }
    }

    class SharedPreferencesProfileStore(context: Context) : ProfileStore {
        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun read(key: String): String? = prefs.getString(key, null)
        override fun write(key: String, value: String) {
            prefs.edit().putString(key, value).apply()
        }
    }

    /**
     * Technical (non-private) bucketed description of one device + source + encoder combination.
     */
    data class EncodeProfileKey(
        val manufacturer: String,
        val model: String,
        val sdkInt: Int,
        val encoderMime: String,
        val sourceCodec: String,
        val resolutionBucket: String,
        val fpsBucket: String,
        val hdrBucket: String,
        val bitrateBucket: String,
        val audioBucket: String
    ) {
        fun asKey(): String = listOf(
            sanitize(manufacturer),
            sanitize(model),
            sdkInt.toString(),
            sanitize(encoderMime),
            sanitize(sourceCodec),
            resolutionBucket,
            fpsBucket,
            hdrBucket,
            bitrateBucket,
            audioBucket
        ).joinToString("|")

        private fun sanitize(value: String): String =
            value.lowercase().replace(Regex("[|;=\\s]+"), "_").ifBlank { "unknown" }
    }

    data class LearnedEncodeProfile(
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val consecutiveHighRatioFailures: Int = 0,
        val nextTargetRatio: Double? = null,
        val preferRemux: Boolean = false,
        val lastFailureReason: String? = null,
        val lastOutputToSourceRatio: Double? = null,
        // Measured encoder overshoot (actual video bitrate / requested video bitrate) for this
        // profile, e.g. the S23 Ultra HEVC VBR path measured ~1.25 on 4K60 HDR. Used only to make
        // pre-encode size prediction honest; never to relax verification.
        val measuredOvershootFactor: Double? = null
    ) {
        fun encode(): String = listOf(
            "success=$successCount",
            "failure=$failureCount",
            "highFail=$consecutiveHighRatioFailures",
            "nextRatio=${nextTargetRatio ?: ""}",
            "preferRemux=$preferRemux",
            "lastReason=${lastFailureReason.orEmpty().replace(";", ",")}",
            "lastSizeRatio=${lastOutputToSourceRatio ?: ""}",
            "overshoot=${measuredOvershootFactor ?: ""}"
        ).joinToString(";")

        companion object {
            fun decode(raw: String?): LearnedEncodeProfile? {
                if (raw.isNullOrBlank()) return null
                val fields = raw.split(";").mapNotNull { entry ->
                    val idx = entry.indexOf('=')
                    if (idx <= 0) null else entry.substring(0, idx) to entry.substring(idx + 1)
                }.toMap()
                return runCatching {
                    LearnedEncodeProfile(
                        successCount = fields["success"]?.toIntOrNull() ?: 0,
                        failureCount = fields["failure"]?.toIntOrNull() ?: 0,
                        consecutiveHighRatioFailures = fields["highFail"]?.toIntOrNull() ?: 0,
                        nextTargetRatio = fields["nextRatio"]?.toDoubleOrNull(),
                        preferRemux = fields["preferRemux"]?.toBooleanStrictOrNull() ?: false,
                        lastFailureReason = fields["lastReason"]?.ifBlank { null },
                        lastOutputToSourceRatio = fields["lastSizeRatio"]?.toDoubleOrNull(),
                        measuredOvershootFactor = fields["overshoot"]?.toDoubleOrNull()
                    )
                }.getOrNull()
            }
        }
    }

    fun profile(key: EncodeProfileKey): LearnedEncodeProfile =
        LearnedEncodeProfile.decode(store.read(key.asKey())) ?: LearnedEncodeProfile()

    /**
     * The target ratio to use for the next Perceptually Lossless encode of this profile, always
     * clamped inside [max(floorRatio, defaultRatio), PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO].
     * Returns [defaultRatio] (also clamped) when nothing was learned yet.
     *
     * The lower bound includes [defaultRatio] since the 2026-07-14 VMAF suite: structural
     * verification cannot see perceptual damage, so structural-only "success" history may never
     * buy a target below the codec-appropriate default. The proven failure was pair
     * f030dffc553d, walked down to ratio 0.60 by structural passes -> VMAF mean 87.4 / 1%-low
     * 76.7 while labeled "Perceptually Lossless Verified". Learning may still raise the target
     * (after failures) or latch remux preference — both safety-direction moves.
     */
    fun recommendedTargetRatio(key: EncodeProfileKey, defaultRatio: Double, floorRatio: Double): Double {
        val learned = profile(key).nextTargetRatio ?: defaultRatio
        val lowerBound = maxOf(floorRatio, defaultRatio)
            .coerceAtMost(BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO)
        return learned.coerceIn(lowerBound, BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO)
    }

    /**
     * True when this profile has repeatedly failed verification even near the maximum safe target,
     * so future runs should keep the exact stream copy instead of re-encoding.
     */
    fun shouldPreferRemux(key: EncodeProfileKey): Boolean = profile(key).preferRemux

    /**
     * The expected encoder overshoot factor (actual/requested video bitrate) for this profile.
     * Defaults to [DEFAULT_OVERSHOOT_FACTOR] (no overshoot assumed) until measured; always clamped
     * to a sane range so a corrupt store cannot poison prediction.
     */
    fun expectedOvershootFactor(key: EncodeProfileKey): Double =
        (profile(key).measuredOvershootFactor ?: seededDefaultOvershoot(key))
            .coerceIn(MIN_OVERSHOOT_FACTOR, MAX_OVERSHOOT_FACTOR)

    /**
     * Record a verification-passed Perceptually Lossless encode. Since the 2026-07-14 VMAF suite
     * ([SUCCESS_STEP_DOWN] = 0.0) a structural pass no longer steps the next target DOWN: the
     * verifier is structural-only and repeatedly rewarded encodes that VMAF later proved visibly
     * degraded (f030dffc553d walked 0.90 -> 0.60 -> VMAF 87.4). Success still clears failure
     * latches and records the measured overshoot.
     */
    fun recordVerifiedSuccess(
        key: EncodeProfileKey,
        usedTargetRatio: Double,
        outputToSourceBytesRatio: Double,
        floorRatio: Double,
        measuredOvershootFactor: Double? = null
    ): LearnedEncodeProfile {
        val current = profile(key)
        val next = (usedTargetRatio - SUCCESS_STEP_DOWN)
            .coerceIn(floorRatio, BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO)
        val updated = current.copy(
            successCount = current.successCount + 1,
            consecutiveHighRatioFailures = 0,
            nextTargetRatio = next,
            preferRemux = false,
            lastFailureReason = null,
            lastOutputToSourceRatio = outputToSourceBytesRatio,
            measuredOvershootFactor = blendOvershoot(current.measuredOvershootFactor, measuredOvershootFactor)
        )
        store.write(key.asKey(), updated.encode())
        return updated
    }

    /**
     * Record a failed/discarded Perceptually Lossless attempt (verification failed, output grew
     * beyond tolerance, HDR/FPS/audio not proven retained, ...). The next recommendation steps up,
     * and repeated failures near the maximum ratio mark the profile as remux-preferred.
     */
    fun recordFailure(
        key: EncodeProfileKey,
        usedTargetRatio: Double,
        reason: String,
        floorRatio: Double,
        measuredOvershootFactor: Double? = null
    ): LearnedEncodeProfile {
        val current = profile(key)
        val next = (usedTargetRatio + FAILURE_STEP_UP)
            .coerceIn(
                floorRatio.coerceAtMost(BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO),
                BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO
            )
        val nearMax = usedTargetRatio >= HIGH_RATIO_FAILURE_THRESHOLD
        val highRatioFailures = if (nearMax) current.consecutiveHighRatioFailures + 1 else 0
        val updated = current.copy(
            failureCount = current.failureCount + 1,
            consecutiveHighRatioFailures = highRatioFailures,
            nextTargetRatio = next,
            preferRemux = highRatioFailures >= HIGH_RATIO_FAILURES_BEFORE_REMUX,
            lastFailureReason = reason,
            measuredOvershootFactor = blendOvershoot(current.measuredOvershootFactor, measuredOvershootFactor)
        )
        store.write(key.asKey(), updated.encode())
        return updated
    }

    // Average the new measurement with the stored one so a single outlier run cannot swing the
    // prediction; clamped into [MIN_OVERSHOOT_FACTOR, MAX_OVERSHOOT_FACTOR].
    private fun blendOvershoot(stored: Double?, measured: Double?): Double? {
        val clampedMeasured = measured?.takeIf { it.isFinite() && it > 0 }
            ?.coerceIn(MIN_OVERSHOOT_FACTOR, MAX_OVERSHOOT_FACTOR)
            ?: return stored
        val base = stored?.coerceIn(MIN_OVERSHOOT_FACTOR, MAX_OVERSHOOT_FACTOR)
        return if (base == null) clampedMeasured else (base + clampedMeasured) / 2.0
    }

    companion object {
        // v3: the 2026-07-14 VMAF suite (validation\vmaf_analysis\PIXEL_QUALITY_REPORT.md) proved
        // that ratios learned from structural-only verification are perceptually unsafe (measured
        // learned ratios 0.60-0.88 all failed the pixel thresholds). Any nextTargetRatio stored
        // under the v2 name was calibrated against that blind feedback loop, so the store name is
        // bumped to orphan the poisoned state. (v2 note, kept for history: the pre-fix build
        // applied a camera-class absolute bitrate floor to every source, which clamped
        // downloaded/low-bitrate videos' targets up to the source bitrate and stream-copied them;
        // v2 orphaned state calibrated against that bug.)
        private const val PREFS_NAME = "smart_perceptual_profiles_v3"

        // Adaptation is safety-only since the 2026-07-14 VMAF evidence: NO step-down after
        // structural successes (the structural verifier cannot see perceptual damage, so success
        // history must never buy a lower target), larger steps up after failures.
        const val SUCCESS_STEP_DOWN = 0.0
        const val FAILURE_STEP_UP = 0.05
        const val HIGH_RATIO_FAILURE_THRESHOLD = 0.93
        const val HIGH_RATIO_FAILURES_BEFORE_REMUX = 2

        // Overshoot factors are ratios of measured/requested video bitrate. 1.0 = encoder honored
        // the request exactly; the S23 Ultra HEVC VBR path measured ~1.25 on 4K60 HDR sources.
        const val DEFAULT_OVERSHOOT_FACTOR = 1.0
        const val MIN_OVERSHOOT_FACTOR = 1.0
        const val MAX_OVERSHOOT_FACTOR = 2.0

        // First-encounter (pre-measurement) overshoot seed for the near-ceiling S23-class HDR HEVC
        // classes only. Documented device behavior: the QTI HEVC VBR encoder overshoots ~1.25x on
        // high-resolution HDR footage that is already near the hardware efficiency ceiling. Set to
        // 1.0 to disable the seed entirely (see [seededDefaultOvershoot] for the trade-off).
        const val SEEDED_NEAR_CEILING_OVERSHOOT = 1.25

        /**
         * Overshoot factor to assume for a profile that has not yet measured its own encoder behavior.
         * Most profiles get [DEFAULT_OVERSHOOT_FACTOR] (assume the encoder honors the request). The
         * S23-class high-resolution HDR HEVC->HEVC classes get [SEEDED_NEAR_CEILING_OVERSHOOT] so the
         * FIRST encounter's near-optimal gate predicts no useful saving and prefers an honest remux
         * instead of burning a doomed encode that verification usually discards anyway.
         *
         * Intentional trade-off (revert by setting the seed to 1.0): a first clip of such a bucket that
         * WOULD have compressed a few percent will also prefer remux. That is the skill-endorsed choice
         * for near-ceiling Samsung HDR HEVC camera footage. This only biases the pre-encode encode-vs-
         * remux decision; it never relaxes a bitrate floor or the verifier, and any real per-profile
         * measurement immediately supersedes the seed.
         */
        internal fun seededDefaultOvershoot(key: EncodeProfileKey): Double {
            val hevcToHevc = key.encoderMime.equals(MimeTypes.VIDEO_H265, ignoreCase = true) &&
                key.sourceCodec.equals(MimeTypes.VIDEO_H265, ignoreCase = true)
            val highResolution = key.resolutionBucket == "4k" || key.resolutionBucket == "8k"
            val highFrameRate = key.fpsBucket == "60" || key.fpsBucket == "120"
            val hdr = key.hdrBucket.startsWith("pq") || key.hdrBucket.startsWith("hlg")
            return if (hevcToHevc && highResolution && highFrameRate && hdr) {
                SEEDED_NEAR_CEILING_OVERSHOOT
            } else {
                DEFAULT_OVERSHOOT_FACTOR
            }
        }

        fun profileKeyFor(
            source: VideoSourceInfo,
            encoderMime: String,
            manufacturer: String,
            model: String,
            sdkInt: Int
        ): EncodeProfileKey {
            return EncodeProfileKey(
                manufacturer = manufacturer,
                model = model,
                sdkInt = sdkInt,
                encoderMime = encoderMime,
                sourceCodec = source.videoMime ?: "unknown",
                resolutionBucket = resolutionBucket(source.width, source.height),
                fpsBucket = fpsBucket(source.frameRate),
                hdrBucket = hdrBucket(source),
                bitrateBucket = bitrateBucket(source.videoBitrate),
                audioBucket = audioBucket(source)
            )
        }

        fun resolutionBucket(width: Int, height: Int): String {
            val longEdge = maxOf(width, height)
            return when {
                longEdge >= 7680 || minOf(width, height) >= 4320 -> "8k"
                longEdge >= 3840 || minOf(width, height) >= 2160 -> "4k"
                longEdge >= 2560 -> "1440p"
                longEdge >= 1920 -> "1080p"
                longEdge >= 1280 -> "720p"
                longEdge > 0 -> "sd"
                else -> "unknown"
            }
        }

        fun fpsBucket(fps: Float): String {
            return when {
                fps <= 0f -> "variable"
                fps < 27f -> "24"
                fps < 45f -> "30"
                fps < 90f -> "60"
                fps <= 250f -> "120"
                else -> "variable"
            }
        }

        fun hdrBucket(source: VideoSourceInfo): String {
            val transfer = when (source.colorTransfer) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> "pq"
                MediaFormat.COLOR_TRANSFER_HLG -> "hlg"
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "sdr"
                null -> "unknown"
                else -> "t${source.colorTransfer}"
            }
            val bt2020 = source.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
            return if (bt2020) "$transfer-bt2020" else transfer
        }

        fun bitrateBucket(videoBitrate: Int): String {
            return when {
                videoBitrate <= 0 -> "unknown"
                videoBitrate < 10_000_000 -> "lt10m"
                videoBitrate < 25_000_000 -> "10-25m"
                videoBitrate < 50_000_000 -> "25-50m"
                videoBitrate < 80_000_000 -> "50-80m"
                videoBitrate < 120_000_000 -> "80-120m"
                else -> "gte120m"
            }
        }

        fun audioBucket(source: VideoSourceInfo): String {
            if (!source.audioPresent) return "none"
            val codec = source.audioMime?.substringAfter('/') ?: "unknown"
            val rate = when {
                source.audioBitrate <= 0 -> "unknown"
                source.audioBitrate < 160_000 -> "lt160k"
                source.audioBitrate < 288_000 -> "160-288k"
                else -> "gte288k"
            }
            return "$codec-$rate"
        }
    }
}
