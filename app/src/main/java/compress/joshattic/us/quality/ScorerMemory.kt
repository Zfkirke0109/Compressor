package compress.joshattic.us.quality

/** Window-boundary snapshots, explicitly not a claim to have measured the true peak. */
internal object ScorerMemory {
    fun snapshot(): Map<String, Any?> = runCatching {
        val runtime = Runtime.getRuntime()
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        linkedMapOf<String, Any?>(
            "javaUsedBytes" to runtime.totalMemory() - runtime.freeMemory(),
            "javaMaxBytes" to runtime.maxMemory(),
            "nativeAllocatedBytes" to android.os.Debug.getNativeHeapAllocatedSize(),
            "totalPssKiB" to info.totalPss,
            "sampling" to "window-boundary-not-high-water"
        )
    }.getOrDefault(emptyMap())
}
