from pathlib import Path

p = Path("app/src/main/java/compress/joshattic/us/BatchCompressorViewModel.kt")
s = p.read_text()

# Fix setQuality(): it now calls estimateOutputSize(..., codec, frameRate),
# so it needs codec/frameRate variables in that scope.
old = """            val quality = qualityFromLabel(label)
            state.copy("""
new = """            val quality = qualityFromLabel(label)
            val codec = codecFromLabel(state.codecOption)
            val frameRate = frameRateFromLabel(state.frameRateOption)
            state.copy("""
if old in s and "val codec = codecFromLabel(state.codecOption)" not in s[s.find(old):s.find(old)+300]:
    s = s.replace(old, new, 1)

# Fix the remaining startCompression reset call that still uses the old 2-argument estimateOutputSize().
s = s.replace(
    "                                targetOutputSize = estimateOutputSize(item, quality),",
    "                                targetOutputSize = estimateOutputSize(item, quality, codec, frameRate),"
)

p.write_text(s)
print("Fixed Phase 1 build errors for codec/frameRate.")
