package compress.joshattic.us

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log

/**
 * Diagnostics-only encoder capability dumper (no behavior change, debug builds only).
 *
 * Logs, under the tag [TAG], the ground truth this device actually exposes for every video
 * encoder: bitrate modes (CQ/VBR/CBR), quality range, complexity range, QP-bounds feature,
 * profiles/levels, color formats (including 10-bit), resolution/rate/bitrate ranges, HDR-relevant
 * HEVC profiles, AV1/APV presence, and discovered vendor parameter names (API 31+).
 *
 * Read with:
 *   adb logcat -v time -s CompressorCodecCaps
 *
 * Nothing here configures an encoder or changes encode behavior; vendor parameter names are
 * discovered (read-only) so experiments elsewhere can be gated on real device evidence instead
 * of assumptions copied from the internet.
 */
object EncoderCapabilityDiagnostics {
    const val TAG = "CompressorCodecCaps"

    private val INTERESTING_VIDEO_MIMES = listOf(
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_AVC,
        MediaFormat.MIMETYPE_VIDEO_AV1,
        "video/apv"
    )

    @Volatile
    private var dumped = false

    /** Fire-and-forget dump on a background thread; once per process; debug builds only. */
    fun dumpOnceInBackground(context: Context) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || dumped) return
        dumped = true
        Thread({ runCatching { dumpToLogcat() }.onFailure { Log.w(TAG, "dump failed", it) } }, "CodecCapsDump").start()
    }

    fun dumpToLogcat() {
        Log.i(TAG, "device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}); soc=${socModel()}; sdk=${Build.VERSION.SDK_INT}; release=${Build.VERSION.RELEASE}")
        val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos

        // APV / unusual mime scan across ALL codecs (encoders and decoders).
        codecInfos.forEach { info ->
            val apvLike = info.name.contains("apv", ignoreCase = true) ||
                info.supportedTypes.any { it.contains("apv", ignoreCase = true) }
            if (apvLike) {
                Log.i(TAG, "APV-like codec found: name=${info.name}; encoder=${info.isEncoder}; types=${info.supportedTypes.joinToString()}")
            }
        }
        if (codecInfos.none { info ->
                info.name.contains("apv", ignoreCase = true) ||
                    info.supportedTypes.any { it.contains("apv", ignoreCase = true) }
            }
        ) {
            Log.i(TAG, "APV: no encoder or decoder exposed on this device (searched names and mime types)")
        }

        for (mime in INTERESTING_VIDEO_MIMES) {
            val encoders = codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            if (encoders.isEmpty()) {
                Log.i(TAG, "mime=$mime: no encoders")
                continue
            }
            encoders.forEach { info -> dumpEncoder(info, mime) }
        }
    }

    private fun dumpEncoder(info: MediaCodecInfo, mime: String) {
        runCatching {
            val caps = info.getCapabilitiesForType(mime)
            val enc = caps.encoderCapabilities
            val video = caps.videoCapabilities
            val hw = if (Build.VERSION.SDK_INT >= 29) {
                "hw=${info.isHardwareAccelerated} sw-only=${info.isSoftwareOnly} vendor=${info.isVendor}"
            } else {
                "hw=unknown(sdk<29)"
            }
            Log.i(TAG, "encoder=${info.name}; mime=$mime; $hw")
            if (enc != null) {
                val cq = enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                val vbr = enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                val cbr = enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                val cbrFd = if (Build.VERSION.SDK_INT >= 31) {
                    enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)
                } else {
                    false
                }
                Log.i(TAG, "  bitrateModes: CQ=$cq VBR=$vbr CBR=$cbr CBR_FD=$cbrFd")
                Log.i(TAG, "  qualityRange=${enc.qualityRange}; complexityRange=${enc.complexityRange}")
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val qpBounds = runCatching {
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds)
                }.getOrDefault(false)
                Log.i(TAG, "  FEATURE_QpBounds=$qpBounds")
                val hdrEditing = runCatching {
                    caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
                }.getOrDefault(false)
                Log.i(TAG, "  FEATURE_HdrEditing=$hdrEditing")
            } else {
                Log.i(TAG, "  FEATURE_QpBounds=unavailable(sdk<31)")
            }
            if (video != null) {
                Log.i(
                    TAG,
                    "  video: widths=${video.supportedWidths} heights=${video.supportedHeights} " +
                        "bitrateRange=${video.bitrateRange} fpsRange=${video.supportedFrameRates}"
                )
            }
            val profiles = caps.profileLevels.joinToString { "p=0x${Integer.toHexString(it.profile)}/l=0x${Integer.toHexString(it.level)}" }
            Log.i(TAG, "  profileLevels: $profiles")
            if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                val main10 = caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                val main10Hdr10 = caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }
                Log.i(TAG, "  HEVC Main10=$main10; Main10HDR10(+)=$main10Hdr10")
            }
            val colorFormats = caps.colorFormats.joinToString { "0x${Integer.toHexString(it)}" }
            Log.i(TAG, "  colorFormats: $colorFormats")

            dumpVendorParameters(info.name, mime)
        }.onFailure { Log.w(TAG, "capability dump failed for ${info.name}/$mime", it) }
    }

    /**
     * Read-only vendor parameter discovery (API 31+). Creates the codec by name only to call
     * [MediaCodec.getSupportedVendorParameters]; never configures or starts it.
     */
    private fun dumpVendorParameters(codecName: String, mime: String) {
        if (Build.VERSION.SDK_INT < 31) {
            Log.i(TAG, "  vendorParams: unavailable(sdk<31)")
            return
        }
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(codecName)
            val params = codec.supportedVendorParameters
            if (params.isEmpty()) {
                Log.i(TAG, "  vendorParams($mime): none exposed")
            } else {
                // Log in chunks to survive logcat line limits.
                params.chunked(8).forEachIndexed { i, chunk ->
                    Log.i(TAG, "  vendorParams[$i]: ${chunk.joinToString()}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "  vendorParams: discovery failed for $codecName (${t.javaClass.simpleName})")
        } finally {
            runCatching { codec?.release() }
        }
    }

    private fun socModel(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown(sdk<31)"
}
