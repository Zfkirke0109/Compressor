package compress.joshattic.us

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import compress.joshattic.us.quality.QualityProbePolicy

/**
 * Identities a capture needs to say two runs measured the same things with the same tools
 * (b177 F4). URI-derived job ids identify a NAME; these identify content, models and encoders.
 */
object SourceFingerprint {
    const val CHUNK = 1 shl 20

    /** What the fingerprint is, stated in the record so nobody mistakes it for a full hash. */
    const val BASIS = "sha256(size + first/middle/last 1 MiB); sampled, not a full-content hash"

    /** Offsets of the sampled chunks for a file of [size] bytes (fewer when the file is small). */
    fun offsets(size: Long): List<Long> = when {
        size <= 0L -> emptyList()
        size <= 3L * CHUNK -> listOf(0L)
        else -> listOf(0L, size / 2 - CHUNK / 2, size - CHUNK)
    }

    /** Pure core: hash of the size and the sampled chunks, in order. */
    fun digest(size: Long, chunks: List<ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(size.toString().toByteArray(Charsets.US_ASCII))
        chunks.forEach { md.update(it) }
        return md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    /** Reads the chunks of [uri]; null when the source cannot be read. Never throws. */
    fun of(context: Context, uri: Uri, size: Long): String? = runCatching {
        val chunks = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                offsets(size).map { offset ->
                    val len = minOf(if (size <= 3L * CHUNK) size else CHUNK.toLong(), size - offset).toInt()
                    val buf = java.nio.ByteBuffer.allocate(len)
                    var pos = offset
                    while (buf.hasRemaining()) {
                        val n = ch.read(buf, pos)
                        if (n <= 0) break
                        pos += n
                    }
                    buf.array().copyOf(buf.position())
                }
            }
        } ?: return null
        digest(size, chunks)
    }.getOrNull()

    /** One hash over every job's fingerprint, order-independent: the batch's source manifest. */
    fun manifest(entries: Map<String, String?>): String =
        digest(entries.size.toLong(), entries.toSortedMap().map { (k, v) -> "$k=${v ?: "unreadable"}\n".toByteArray(Charsets.UTF_8) })
}

/**
 * SHA-256 of every byte of a source, for the pilot's opt-in source check (EncoderExperiments
 * .isFullSourceHashEnabled). [SourceFingerprint] samples 3 MiB and cannot prove two files are
 * identical or that an original was left untouched; this can, at the cost of reading the file.
 */
object FullSourceHash {
    const val BUFFER = 1 shl 20

    data class Result(val sha256: String?, val bytes: Long, val elapsedMs: Long, val error: String?)

    /**
     * Pure core: hashes [input] to its end. [checkpoint] runs before every read, so a cancelled
     * batch stops within one buffer; whatever it throws propagates.
     */
    fun digest(input: java.io.InputStream, checkpoint: () -> Unit = {}): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            checkpoint()
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
        }
        return md.digest().joinToString("") { String.format(Locale.US, "%02x", it) } to total
    }

    /**
     * Hashes [uri] through the content resolver. A read failure is returned as [Result.error],
     * never thrown; cancellation (from [checkpoint]) is rethrown.
     */
    fun of(context: Context, uri: Uri, checkpoint: () -> Unit = {}): Result {
        val start = android.os.SystemClock.elapsedRealtime()
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return Result(null, 0L, android.os.SystemClock.elapsedRealtime() - start, "source could not be opened")
            val (sha, bytes) = stream.use { digest(it, checkpoint) }
            Result(sha, bytes, android.os.SystemClock.elapsedRealtime() - start, null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(null, 0L, android.os.SystemClock.elapsedRealtime() - start, e.javaClass.simpleName)
        }
    }
}

/** Version and file identity of the native scoring libraries and the models they run. */
object ScoringIdentity {
    /** Frozen production decision constants, read from the gate itself rather than copied values. */
    fun frozenGate(): Map<String, Any> = mapOf(
        "verdictModel" to "vmaf_v0.6.1",
        "phoneModel" to compress.joshattic.us.quality.VmafPairScorer.PRODUCTION_PHONE_MODEL,
        "windowMeanMin" to QualityProbePolicy.WINDOW_MEAN_MIN,
        "windowP5Min" to QualityProbePolicy.WINDOW_P5_MIN,
        "windowMinMin" to QualityProbePolicy.WINDOW_MIN_MIN,
        "probeSelectionMargins" to mapOf(
            "mean" to QualityProbePolicy.PROBE_SELECTION_MARGIN_MEAN,
            "p5" to QualityProbePolicy.PROBE_SELECTION_MARGIN_P5,
            "min" to QualityProbePolicy.PROBE_SELECTION_MARGIN_MIN
        ),
        "minComparedFramesPerWindow" to QualityProbePolicy.MIN_COMPARED_FRAMES_PER_WINDOW
    )

    fun describe(context: Context, vmafVersion: String?, v1Model: String?): Map<String, Any?> {
        val libDir = runCatching { File(context.applicationInfo.nativeLibraryDir) }.getOrNull()
        fun libHash(name: String): String = runCatching {
            val f = File(libDir, name)
            if (!f.isFile) return@runCatching "not_extracted"
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
        }.getOrDefault("unreadable")
        return linkedMapOf(
            "verdictModel" to "vmaf_v0.6.1",
            "verdictPhoneModel" to compress.joshattic.us.quality.VmafPairScorer.PRODUCTION_PHONE_MODEL,
            "frozenGate" to frozenGate(),
            "libvmafVersion" to vmafVersion,
            "shadowModel" to v1Model,
            "libcompressorvmafSha256" to libHash("libcompressorvmaf.so"),
            "libcompressorvmafv1Sha256" to libHash("libcompressorvmafv1.so")
        )
    }
}

/**
 * What the device's video encoders say they support (b177 WP3 capability inventory): names,
 * hardware/software/vendor flags, bitrate modes, complexity and quality ranges, and profile count
 * per MIME type. This is what the codec ADVERTISES; whether a request is honoured is a separate
 * measurement (EncoderConfigDelta and the bitstream).
 */
object EncoderInventory {
    val MIMES = listOf("video/hevc", "video/avc", "video/av01", "video/x-vnd.on2.vp9")

    data class Entry(
        val name: String,
        val mime: String,
        val hardware: Boolean?,
        val softwareOnly: Boolean?,
        val vendor: Boolean?,
        val alias: Boolean?,
        val bitrateModes: List<String>,
        val complexity: String?,
        val quality: String?,
        val profileLevels: Int,
        val tenBit: Boolean
    ) {
        fun compact(): String = buildString {
            append(name).append('|').append(mime)
            append("|hw=").append(hardware ?: "?").append("|sw=").append(softwareOnly ?: "?").append("|vendor=").append(vendor ?: "?")
            append("|modes=").append(bitrateModes.joinToString("+").ifEmpty { "none" })
            append("|complexity=").append(complexity ?: "n/a").append("|quality=").append(quality ?: "n/a")
            append("|profiles=").append(profileLevels).append("|10bit=").append(tenBit)
        }
    }

    fun modeNames(supported: (Int) -> Boolean): List<String> = listOfNotNull(
        "VBR".takeIf { supported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) },
        "CBR".takeIf { supported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) },
        "CQ".takeIf { supported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) },
        "CBR_FD".takeIf { Build.VERSION.SDK_INT >= 31 && supported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD) }
    )

    /**
     * Profile numbers are per codec, not global: HEVCProfileMain10, AV1ProfileMain10,
     * AVCProfileMain and VP9Profile1 are all 2. So the MIME type decides which numbers mean a
     * 10-bit profile; an unknown MIME advertises none.
     */
    internal fun advertisesTenBit(mime: String, profiles: List<Int>): Boolean {
        val tenBit = TEN_BIT_PROFILES[mime.lowercase(Locale.US)] ?: return false
        return profiles.any { it in tenBit }
    }

    private val TEN_BIT_PROFILES: Map<String, Set<Int>> = mapOf(
        "video/hevc" to setOf(
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        ),
        "video/av01" to setOf(
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
        ),
        "video/x-vnd.on2.vp9" to setOf(
            MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3,
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
            MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus,
            MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
        ),
        "video/avc" to setOf(
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
        )
    )

    fun snapshot(): List<Entry> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }.flatMap { info ->
            info.supportedTypes.filter { it.lowercase(Locale.US) in MIMES }.mapNotNull { mime ->
                runCatching {
                    val caps = info.getCapabilitiesForType(mime)
                    // Null for a codec that is not an encoder for this type; nothing to inventory.
                    val enc = caps.encoderCapabilities ?: return@runCatching null
                    val q10 = Build.VERSION.SDK_INT >= 29
                    Entry(
                        name = info.name,
                        mime = mime,
                        hardware = if (q10) info.isHardwareAccelerated else null,
                        softwareOnly = if (q10) info.isSoftwareOnly else null,
                        vendor = if (q10) info.isVendor else null,
                        alias = if (q10) info.isAlias else null,
                        bitrateModes = modeNames { enc.isBitrateModeSupported(it) },
                        complexity = enc.complexityRange?.let { "${it.lower}..${it.upper}" },
                        quality = if (q10) enc.qualityRange?.let { "${it.lower}..${it.upper}" } else null,
                        profileLevels = caps.profileLevels.size,
                        tenBit = caps.profileLevels.any { pl ->
                            pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                                pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                (Build.VERSION.SDK_INT >= 29 && pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)
                        }
                    )
                }.getOrNull()
            }
        }
    }.getOrDefault(emptyList())
}
