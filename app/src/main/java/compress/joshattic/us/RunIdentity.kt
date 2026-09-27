package compress.joshattic.us

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.Locale

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

/** Version and file identity of the native scoring libraries and the models they run. */
object ScoringIdentity {
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
