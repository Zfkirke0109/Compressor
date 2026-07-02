package compress.joshattic.us

enum class EncoderMode(val reportLabel: String) {
    CQ("CQ"),
    VBR_FALLBACK("VBR fallback"),
    NOT_EXPOSED("not exposed")
}

internal object EncoderModeSelector {
    fun chooseForOriginal(isOriginalMode: Boolean, cqSupported: Boolean): EncoderMode {
        return when {
            !isOriginalMode -> EncoderMode.NOT_EXPOSED
            cqSupported -> EncoderMode.CQ
            else -> EncoderMode.VBR_FALLBACK
        }
    }
}
