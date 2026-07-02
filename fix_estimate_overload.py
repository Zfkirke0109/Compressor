from pathlib import Path

p = Path("app/src/main/java/compress/joshattic/us/BatchCompressorViewModel.kt")
s = p.read_text()

overload = '''    private fun estimateOutputSize(item: BatchVideoItem, quality: BatchQualityPreset): Long {
        val codec = codecFromLabel(_uiState.value.codecOption)
        val frameRate = frameRateFromLabel(_uiState.value.frameRateOption)
        return estimateOutputSize(item, quality, codec, frameRate)
    }

'''

needle = '''    private fun estimateOutputSize(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        codec: BatchCodecOption,
        frameRate: BatchFrameRateOption
    ): Long {'''

if overload not in s:
    if needle not in s:
        raise SystemExit("Could not find the 4-argument estimateOutputSize function.")
    s = s.replace(needle, overload + needle, 1)

p.write_text(s)
print("Added compatibility overload for old estimateOutputSize(item, quality) calls.")
