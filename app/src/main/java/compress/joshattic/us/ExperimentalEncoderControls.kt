package compress.joshattic.us

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * Experimental encoder-ceiling diagnostics for Smart Perceptually Lossless.
 *
 * Background (measured on SM-S918U1 / SM8550 / Android 16): `c2.qti.hevc.encoder` in VBR mode
 * overshoots the requested bitrate by ~25% on 4K60 HDR camera sources (113.9 Mbps requested ->
 * 142.3 Mbps measured), which makes conservative Perceptually Lossless targets produce outputs
 * larger than the source. Device probing also showed:
 *  - CBR is supported by the hardware HEVC/AVC encoders (public API reachable);
 *  - CQ is only exposed by 512x512-capped variants (unusable for real video);
 *  - FEATURE_QpBounds is false on every hardware encoder (standard QP keys unusable);
 *  - vendor.qti-ext-enc-* keys exist but require a custom encoder factory (future tier).
 *
 * Tier 1 (this flag): request BITRATE_MODE_CBR for Smart PL encode attempts, disable Media3's
 * silent format fallback for PL, and log requested vs. measured bitrate plus the encoder name.
 *
 * Truth rules are untouched: OutputVerifier still decides "verified", oversized or unproven
 * outputs are still discarded, production Remux fallback stays intact, and replacement remains
 * blocked unless verification passes AND the output is strictly smaller than the source.
 * This flag only changes HOW an encode attempt is requested, never how it is judged.
 */
object ExperimentalEncoderControls {

    /**
     * Master switch for the Tier-1 encoder-ceiling experiment. Even when true, the experiment is
     * active only in debuggable builds (see [isEnabled]) so release behavior stays untouched.
     */
    // OFF since b164. Every debug build the user has run made its real Perceptually Lossless
    // encodes in CBR under this flag, which was written as a diagnostic, not as the production
    // mode. CBR spends bits evenly across frames, so the hardest frames of a window get the
    // least help exactly where the per-frame minimum gate looks; VBR lets the encoder move bits
    // to those frames, which is what Perceptually Lossless needs at a given size. The size risk
    // the flag guarded against (VBR overshoot) is already handled by measured overshoot and by
    // verification requiring a strictly smaller output. Set to true only for a deliberate
    // encoder-ceiling experiment, and record it in the capture.
    const val ENABLE_EXPERIMENTAL_ENCODER_CEILING_DIAGNOSTICS = false

    fun isEnabled(context: Context): Boolean {
        if (!ENABLE_EXPERIMENTAL_ENCODER_CEILING_DIAGNOSTICS) return false
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return debuggable
    }

    /**
     * True when a hardware encoder for [mimeType] reports CBR support. Device-probed at call time
     * (never assumed): the experiment silently stays on VBR when CBR is not actually available.
     */
    fun isCbrSupportedByHardwareEncoder(mimeType: String): Boolean {
        return runCatching {
            DeviceCodecCatalog.codecInfos.any { info ->
                info.isEncoder &&
                    (android.os.Build.VERSION.SDK_INT < 29 || !info.isSoftwareOnly) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } &&
                    runCatching {
                        info.getCapabilitiesForType(mimeType).encoderCapabilities
                            ?.isBitrateModeSupported(
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                            ) == true
                    }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }
}
