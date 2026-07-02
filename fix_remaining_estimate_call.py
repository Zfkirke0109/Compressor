from pathlib import Path

p = Path("app/src/main/java/compress/joshattic/us/BatchCompressorViewModel.kt")
s = p.read_text()

s = s.replace(
    "targetOutputSize = estimateOutputSize(item, quality),",
    "targetOutputSize = estimateOutputSize(item, quality, codec, frameRate),"
)

s = s.replace(
    "formatFileSize(estimateOutputSize(item, quality))",
    "formatFileSize(estimateOutputSize(item, quality, codec, frameRate))"
)

p.write_text(s)
print("Fixed remaining old estimateOutputSize calls.")
