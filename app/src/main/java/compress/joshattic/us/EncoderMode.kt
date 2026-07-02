package compress.joshattic.us

enum class EncoderMode(val reportLabel: String) {
    HIGH_QUALITY("high-quality VBR"),
    VBR_FALLBACK("VBR fallback"),
    NOT_EXPOSED("not exposed")
}

internal object EncoderModeSelector {
    fun chooseInitialMode(isOriginalMode: Boolean): EncoderMode {
        return when {
            !isOriginalMode -> EncoderMode.NOT_EXPOSED
            else -> EncoderMode.HIGH_QUALITY
        }
    }
}
