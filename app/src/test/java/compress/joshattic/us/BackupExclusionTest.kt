package compress.joshattic.us

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What must never leave the device in an Android backup or a device-to-device transfer.
 *
 * `replace_recovery` holds rollback copies of a user's original video. `diagnostics` holds each
 * run's session records (full source SHA-256s and source fingerprints), its frame traces, and,
 * when the experiment that keeps them is on, encoded copies of the user's videos, up to 512 MiB a
 * run. Both describe this device only; exporting diagnostics is the user's explicit act.
 */
class BackupExclusionTest {

    private fun rules(name: String): String =
        Media3Host.inModule("src/main/res/xml/$name").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun section(xml: String, tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: throw AssertionError("no <$tag> section")

    private fun assertExcluded(where: String, xml: String, path: String) =
        assertTrue("$where must exclude $path", Regex("""<exclude\s+domain="file"\s+path="$path"\s*/>""").containsMatchIn(xml))

    @Test fun privateFilesAreKeptOutOfCloudBackupAndDeviceTransfer() {
        val extraction = rules("data_extraction_rules.xml")
        for (path in listOf("replace_recovery", "diagnostics")) {
            assertExcluded("cloud-backup", section(extraction, "cloud-backup"), path)
            assertExcluded("device-transfer", section(extraction, "device-transfer"), path)
        }
    }

    @Test fun privateFilesAreKeptOutOfTheBackupOfOlderAndroidVersions() {
        val full = section(rules("backup_rules.xml"), "full-backup-content")
        for (path in listOf("replace_recovery", "diagnostics")) assertExcluded("full-backup-content", full, path)
    }
}
