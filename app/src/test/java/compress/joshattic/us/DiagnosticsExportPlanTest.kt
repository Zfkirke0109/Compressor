package compress.joshattic.us

import compress.joshattic.us.DiagnosticsExportPlan.TermuxAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device diagnostics export exists because the transport, not the analysis, has been the
 * bottleneck: the recorder's files are unreachable over adb from Secure Folder, and the logcat
 * workaround loses records to a 5 MiB buffer. These pin the rules that keep an export both usable
 * and honest — it must never overwrite prior evidence, never read another app's log, and never
 * claim a Termux capability it cannot verify.
 */
class DiagnosticsExportPlanTest {

    @Test
    fun logcatDumpIsScopedToThisProcess() {
        // --pid is what makes this work WITHOUT the privileged READ_LOGS permission, and it is
        // also what keeps other apps' output out of a file the user is about to share.
        val args = DiagnosticsExportPlan.logcatDumpArgs(pid = 25965)
        assertTrue("--pid=25965" in args)
        assertEquals("logcat", args.first())
    }

    @Test
    fun logcatDumpExitsInsteadOfStreaming() {
        // Without -d the process never reaches EOF and the caller blocks forever.
        assertTrue("-d" in DiagnosticsExportPlan.logcatDumpArgs(pid = 1))
    }

    @Test
    fun logcatDumpRequestsEveryDiagnosticTag() {
        val args = DiagnosticsExportPlan.logcatDumpArgs(pid = 1)
        for (tag in DiagnosticsExportPlan.DIAGNOSTIC_TAGS) {
            assertTrue("missing tag $tag", "$tag:V" in args)
        }
    }

    @Test
    fun theVerificationTagIsIncludedBecauseTheJsonlDoesNotCarryItsDetail() {
        // CompressorVerification is the only place the per-check detail line is written; dropping
        // it would make the log export redundant with the session records.
        assertTrue("CompressorVerification" in DiagnosticsExportPlan.DIAGNOSTIC_TAGS)
        assertTrue("CompressorDiag" in DiagnosticsExportPlan.DIAGNOSTIC_TAGS)
    }

    @Test
    fun anEmptyTagListIsRejectedRatherThanSilentlyCapturingEverything() {
        // `logcat -s` with no tags is not "no filter" — but an empty list here would also mean the
        // caller has a bug, and producing a file either way would hide it.
        val error = runCatching { DiagnosticsExportPlan.logcatDumpArgs(pid = 1, tags = emptyList()) }
        assertTrue(error.isFailure)
    }

    @Test
    fun anInvalidPidIsRejected() {
        assertTrue(runCatching { DiagnosticsExportPlan.logcatDumpArgs(pid = 0) }.isFailure)
        assertTrue(runCatching { DiagnosticsExportPlan.logcatDumpArgs(pid = -1) }.isFailure)
    }

    @Test
    fun exportFileNamesAreUniquePerExportSoEvidenceIsNeverOverwritten() {
        // An earlier capture is the baseline arm of any comparison. Replacing it silently would
        // destroy the ability to compare two runs at all.
        val first = DiagnosticsExportPlan.exportFileName("compressor-sessions", "batch_1", "20260813-185416")
        val second = DiagnosticsExportPlan.exportFileName("compressor-sessions", "batch_1", "20260813-185417")
        assertTrue(first != second)
    }

    @Test
    fun theBatchIdIsCarriedInTheFileNameWhenKnown() {
        val name = DiagnosticsExportPlan.exportFileName("compressor-sessions", "batch_1786670702437", "20260813-185416")
        assertEquals("compressor-sessions-batch_1786670702437-20260813-185416.txt", name)
    }

    @Test
    fun anAbsentBatchIdIsOmittedRatherThanRenderedAsNullOrBlank() {
        for (id in listOf(null, "", "   ")) {
            val name = DiagnosticsExportPlan.exportFileName("compressor-logcat", id, "20260813-185416")
            assertEquals("compressor-logcat-20260813-185416.txt", name)
        }
    }

    @Test
    fun fileNamesCannotCarryPathSeparatorsIntoMediaStore() {
        // DISPLAY_NAME is not a path; a separator would be rejected or reinterpreted.
        val name = DiagnosticsExportPlan.exportFileName("a/b", "../etc", "20260813")
        assertFalse('/' in name)
        assertFalse("\\" in name)
        assertTrue(name.endsWith(".txt"))
    }

    @Test
    fun sanitisingNeverProducesAnEmptyNameComponent() {
        assertEquals("export", DiagnosticsExportPlan.sanitizeFileNamePart("///"))
        assertEquals("export", DiagnosticsExportPlan.sanitizeFileNamePart(""))
    }

    @Test
    fun sanitisingCollapsesRunsOfIllegalCharacters() {
        assertEquals("a_b", DiagnosticsExportPlan.sanitizeFileNamePart("a???b"))
        assertEquals("batch_1", DiagnosticsExportPlan.sanitizeFileNamePart("batch_1"))
    }

    @Test
    fun termuxIsOnlyReportedAsDrivableWhenBothInstalledAndPermitted() {
        assertEquals(
            TermuxAvailability.CAN_RUN_COMMAND,
            DiagnosticsExportPlan.termuxAvailability(installed = true, runCommandGranted = true)
        )
        assertEquals(
            TermuxAvailability.INSTALLED_WITHOUT_PERMISSION,
            DiagnosticsExportPlan.termuxAvailability(installed = true, runCommandGranted = false)
        )
    }

    @Test
    fun aGrantedPermissionWithoutTermuxInstalledIsStillNotInstalled() {
        // The permission survives an uninstall. Reporting CAN_RUN_COMMAND here would enable a
        // button that cannot possibly work.
        assertEquals(
            TermuxAvailability.NOT_INSTALLED,
            DiagnosticsExportPlan.termuxAvailability(installed = false, runCommandGranted = true)
        )
    }

    @Test
    fun theTermuxScriptClearsTheBufferBeforeCapturing() {
        // The device caps logcat -G at 5 MiB, so a live capture that does not start from an empty
        // buffer loses the beginning of the run — which is where session_start lives.
        val script = DiagnosticsExportPlan.termuxLogcatScript("io.github.zfkirke0109.galaxycompressor")
        val clearAt = script.indexOf("logcat -c")
        val captureAt = script.indexOf("-s CompressorDiag")
        assertTrue("buffer must be cleared", clearAt >= 0)
        assertTrue("capture must follow the clear", captureAt > clearAt)
    }

    @Test
    fun theTermuxScriptCapturesEveryDiagnosticTag() {
        val script = DiagnosticsExportPlan.termuxLogcatScript("io.github.zfkirke0109.galaxycompressor")
        for (tag in DiagnosticsExportPlan.DIAGNOSTIC_TAGS) {
            assertTrue("missing tag $tag", "$tag:V" in script)
        }
    }

    @Test
    fun sizesRenderWithADotDecimalRegardlessOfDeviceLocale() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("512 B", DiagnosticsExportPlan.describeSize(512))
            assertEquals("1.5 KB", DiagnosticsExportPlan.describeSize(1_536))
            assertEquals("2.0 MB", DiagnosticsExportPlan.describeSize(2L * 1024 * 1024))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
