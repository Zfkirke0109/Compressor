package compress.joshattic.us

enum class EncoderMode(val reportLabel: String) {
    HIGH_QUALITY("high-quality VBR"),
    PERCEPTUAL_VBR("conservative VBR"),
    STORAGE_SAVER("storage-saver VBR"),
    VBR_FALLBACK("VBR fallback"),
    NOT_EXPOSED("not exposed")
}

internal object EncoderModeSelector {
    fun chooseInitialMode(quality: BatchQualityPreset): EncoderMode {
        return when (quality) {
            BatchQualityPreset.PERCEPTUALLY_LOSSLESS -> EncoderMode.PERCEPTUAL_VBR
            BatchQualityPreset.HIGH_QUALITY -> EncoderMode.HIGH_QUALITY
            BatchQualityPreset.STORAGE_SAVER -> EncoderMode.STORAGE_SAVER
            BatchQualityPreset.REMUX_ONLY -> EncoderMode.NOT_EXPOSED
        }
    }
}
