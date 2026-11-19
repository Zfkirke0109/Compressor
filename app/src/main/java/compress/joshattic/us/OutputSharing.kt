package compress.joshattic.us

import java.io.File
import java.io.FileInputStream
import java.util.UUID

internal object OutputSharing {
    data class Output(val file: File, val bytes: Long, val displayName: String)

    sealed interface Result {
        data class Ready(val outputs: List<Output>) : Result
        data class Blocked(val message: String) : Result
    }

    // A FileProvider grant may outlive the batch that created it. A later run can reclaim an
    // old file, but must never write different bytes at a path a recipient can still open.
    fun newCacheFile(directory: File, suffix: String, prefix: String = "video-"): File =
        File(directory, "${prefix}${UUID.randomUUID()}$suffix")

    /** All outputs must pass before the caller gives the chooser any read grants. */
    fun prepare(
        outputs: List<Output>,
        removeLocation: Boolean,
        containsLocation: (FileInputStream) -> Boolean
    ): Result {
        if (outputs.isEmpty()) return Result.Blocked("No video outputs are available to share.")
        for (output in outputs) {
            try {
                if (output.bytes <= 0 || !output.file.isFile || output.file.length() != output.bytes) {
                    return Result.Blocked("A video output is no longer available in full. Nothing was shared.")
                }
                if (removeLocation && output.file.inputStream().use(containsLocation)) {
                    return Result.Blocked("A video still contains location metadata. Run it with Remove Location before sharing.")
                }
            } catch (_: Exception) {
                return Result.Blocked("A video output could not be checked. Nothing was shared.")
            }
        }
        return Result.Ready(outputs)
    }
}
