package compress.joshattic.us

/** A Downloads export succeeds only after the complete file is visible to the user. */
internal object DiagnosticsPublication {
    fun writeAndPublish(write: () -> Unit, publish: () -> Int, delete: () -> Unit): Result<Unit> =
        runCatching {
            write()
            check(publish() == 1) { "Downloads did not publish the exported file" }
        }.onFailure {
            // Preserve the original error if provider cleanup also fails.
            runCatching { delete() }
        }
}
