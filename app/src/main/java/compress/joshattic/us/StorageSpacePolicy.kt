package compress.joshattic.us

import android.os.StatFs
import android.util.Log
import java.io.File

/** Outcome of a free-space precheck. [UNKNOWN] means we could not measure, NOT that space is fine. */
enum class SpaceVerdict { SUFFICIENT, INSUFFICIENT, UNKNOWN }

/**
 * Pure free-space policy for operations that write large files.
 *
 * Deliberately blocks only on POSITIVE evidence of insufficient space ([SpaceVerdict.INSUFFICIENT]).
 * When the filesystem cannot be measured the verdict is [SpaceVerdict.UNKNOWN] and callers proceed:
 * refusing to work on every device whose provider will not report free bytes would be a worse
 * failure than the ENOSPC this guards against — and the destructive replace path additionally has a
 * staged recovery copy plus rollback (SAFE-001) as its real backstop. This precheck is the cheap
 * early exit, not the safety net.
 *
 * No Android instances are constructed here, so the decision is unit-testable on the JVM.
 */
object StorageSpacePolicy {

    /**
     * Slack left free after the operation. Large enough that a batch cannot drive the device to a
     * pathological zero-free state (which degrades the whole system), small enough not to block
     * legitimate work on a fullish device.
     */
    const val DEFAULT_HEADROOM_BYTES = 96L * 1024L * 1024L // 96 MB

    /**
     * @param requiredBytes bytes the operation needs to write (0 or negative -> nothing to check).
     * @param availableBytes usable bytes on the target filesystem, or negative when unmeasurable.
     */
    fun verdict(
        requiredBytes: Long,
        availableBytes: Long,
        headroomBytes: Long = DEFAULT_HEADROOM_BYTES
    ): SpaceVerdict = when {
        availableBytes < 0L -> SpaceVerdict.UNKNOWN
        requiredBytes <= 0L -> SpaceVerdict.SUFFICIENT
        availableBytes - headroomBytes >= requiredBytes -> SpaceVerdict.SUFFICIENT
        else -> SpaceVerdict.INSUFFICIENT
    }

    /** True only when we positively know there is not enough room — the sole blocking condition. */
    fun blocks(
        requiredBytes: Long,
        availableBytes: Long,
        headroomBytes: Long = DEFAULT_HEADROOM_BYTES
    ): Boolean = verdict(requiredBytes, availableBytes, headroomBytes) == SpaceVerdict.INSUFFICIENT

    /** Human-readable shortfall for honest messaging; empty when nothing is short. */
    fun shortfallMessage(requiredBytes: Long, availableBytes: Long, headroomBytes: Long = DEFAULT_HEADROOM_BYTES): String {
        if (!blocks(requiredBytes, availableBytes, headroomBytes)) return ""
        val needMb = (requiredBytes + headroomBytes) / (1024L * 1024L)
        val haveMb = availableBytes.coerceAtLeast(0L) / (1024L * 1024L)
        return "needs about ${needMb} MB free but only ${haveMb} MB is available"
    }

    /** Usable bytes on the filesystem holding [path], or -1 when it cannot be measured. */
    fun availableBytesFor(path: File): Long = try {
        StatFs(path.absolutePath).availableBytes
    } catch (t: Throwable) {
        Log.w("StorageSpacePolicy", "could not measure free space: ${t.message}")
        -1L
    }
}
