package compress.joshattic.us

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * The device's codec list, queried once per process.
 *
 * `MediaCodecList(ALL_CODECS)` enumerates and parses every codec the platform exposes, and the
 * cost grew materially on Android 13+. Compressor was building a fresh list at each of five call
 * sites, two of which (`hasEncoder` for HEVC and for AV1, in output-MIME resolution) run for
 * EVERY item in a batch — so a 219-file batch rebuilt the whole catalogue several hundred times
 * to answer a question whose answer cannot change.
 *
 * Learned from upstream JoshAtticus/Compressor `acb76e4` ("performance improvements especially on
 * Android 13+"), which cached the same query for the single-file flow. Our batch flow calls it far
 * more often, so the same fix is worth more here.
 *
 * Caching is sound because the set of installed codecs is fixed for the lifetime of a process:
 * codecs ship with the system image and vendor partitions, and Android exposes no way for them to
 * appear or disappear while the app is running. A construction failure is NOT caught here — Kotlin
 * retries a failed `lazy` initializer on the next access, so transient platform failures are
 * retried rather than permanently cached as an empty list. Callers handle exceptions from the
 * underlying platform query.
 */
object DeviceCodecCatalog {

    /** Every codec on the device, encoders and decoders. Throws if the platform query fails. */
    val codecInfos: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
    }

    /** Encoders only — the common case at every call site in this app. */
    val encoders: List<MediaCodecInfo> by lazy { codecInfos.filter { it.isEncoder } }
}
