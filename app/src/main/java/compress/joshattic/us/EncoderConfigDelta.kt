package compress.joshattic.us

/**
 * What we asked the encoder for, versus what it actually produced.
 *
 * Media3's `DefaultEncoderFactory` applies *format fallback*: when a requested MIME type,
 * resolution, or bitrate is unsupported it silently substitutes something the device can encode
 * and reports success. Requested settings can also simply be ignored or adjusted by the vendor
 * codec. Compressor previously recorded only the requested bitrate, the requested bitrate mode,
 * and the encoder name — nothing that could reveal a substitution.
 *
 * That blind spot matters for a live failure. Two 219-job captures had a 0% pass rate on generated
 * outputs, and a resolution fallback would trip `videoMatches`, which is inside the Perceptually
 * Lossless verification scope. So "the encoder quietly gave us something else" is a testable
 * hypothesis for those rejections rather than speculation — but only once requested and actual are
 * recorded side by side.
 *
 * This type does not decide anything. It reports. A verdict still comes from `OutputVerifier`
 * measuring the finished file, because an encoder's own claim about its output is not evidence
 * that the file on disk matches it.
 *
 * Pure Kotlin, no Android dependencies: fully unit-testable.
 */
data class EncoderConfigDelta(
    val requestedMime: String?,
    val actualMime: String?,
    val requestedWidth: Int,
    val requestedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    val requestedVideoBitrate: Int,
    val actualAverageVideoBitrate: Int,
    val requestedBitrateMode: String?,
    val encoderName: String?
) {

    /** True when the encoder produced a different codec than the one requested. */
    val mimeChanged: Boolean
        get() = requestedMime != null && actualMime != null &&
            !requestedMime.equals(actualMime, ignoreCase = true)

    private val geometryKnown: Boolean
        get() = requestedWidth > 0 && requestedHeight > 0 && actualWidth > 0 && actualHeight > 0

    /**
     * True when the actual geometry is the requested one with the axes swapped — 1080x1920 asked
     * for, 1920x1080 produced.
     *
     * This is not a substitution. Media3 landscape-encodes a portrait source by default and writes
     * a compensating 90/270-degree orientation hint, so the coded frame is transposed while the
     * displayed frame is unchanged; `OutputVerifier.sameDisplayOrientation` accepts exactly this
     * case. Reporting it as a fallback was a false positive — capture batch_1786651683689 flagged
     * three portrait clips (1080x1920, 480x1030, 676x1080) as `FALLBACK=resolution` when nothing
     * had been substituted at all.
     *
     * A square request cannot be transposed, so it never qualifies.
     */
    val orientationTransposed: Boolean
        get() = geometryKnown && requestedWidth != requestedHeight &&
            requestedWidth == actualHeight && requestedHeight == actualWidth

    /**
     * True when the output geometry differs from the request in a way that changes the picture:
     * a different pixel count or aspect, not merely a transposed coding orientation. Unknown
     * values (<= 0) are NOT treated as a change: absent information is not evidence of
     * substitution, and claiming otherwise would manufacture false fallbacks in reports.
     */
    val resolutionChanged: Boolean
        get() = geometryKnown && !orientationTransposed &&
            (requestedWidth != actualWidth || requestedHeight != actualHeight)

    /**
     * Actual average video bitrate as a fraction of what was requested, or null when either side
     * is unknown. Above 1.0 is encoder overshoot; below 1.0 is undershoot. This is a *measurement*,
     * not a pass/fail — the QTI HEVC VBR path is documented at ~1.25 on 4K60 HDR.
     */
    val bitrateRatio: Double?
        get() = if (requestedVideoBitrate > 0 && actualAverageVideoBitrate > 0) {
            actualAverageVideoBitrate.toDouble() / requestedVideoBitrate.toDouble()
        } else {
            null
        }

    /**
     * True when Media3 substituted the codec or the geometry — the two substitutions that change
     * what the verifier will see. Bitrate deviation is deliberately excluded: VBR encoders always
     * miss their target somewhat, so folding it in here would flag nearly every encode and make
     * the signal useless.
     */
    val formatFellBack: Boolean
        get() = mimeChanged || resolutionChanged

    /** Compact, log/JSON-friendly form. Locale-pinned so a comma-decimal device cannot corrupt it. */
    fun compact(): String = buildString {
        append("mime=").append(requestedMime ?: "?").append("->").append(actualMime ?: "?")
        append(";res=").append(requestedWidth).append('x').append(requestedHeight)
            .append("->").append(actualWidth).append('x').append(actualHeight)
        append(";vbr=").append(requestedVideoBitrate).append("->").append(actualAverageVideoBitrate)
        bitrateRatio?.let { append(";ratio=").append(String.format(java.util.Locale.US, "%.3f", it)) }
        append(";mode=").append(requestedBitrateMode ?: "?")
        append(";encoder=").append(encoderName ?: "?")
        // Recorded, not hidden: a transposition is expected for portrait sources, but a capture
        // still has to show that the axes moved, so a genuinely rotated output stays visible.
        if (orientationTransposed) append(";coded=transposed")
        if (formatFellBack) {
            append(";FALLBACK=")
            append(listOfNotNull(
                "mime".takeIf { mimeChanged },
                "resolution".takeIf { resolutionChanged }
            ).joinToString("+"))
        }
    }
}
