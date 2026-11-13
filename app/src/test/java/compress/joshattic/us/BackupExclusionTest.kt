package compress.joshattic.us

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What must never leave the device in an Android backup or a device-to-device transfer.
 *
 * `replace_recovery` holds rollback copies of a user's original video. `diagnostics` holds each
 * run's session records (full source SHA-256s and source fingerprints), its frame traces, and,
 * when the experiment that keeps them is on, encoded copies of the user's videos, up to 512 MiB a
 * run. Both describe this device only; exporting diagnostics is the user's explicit act.
 *
 * The learned profiles (`smart_perceptual_profiles_v4`) and the learning journal (`learning/`)
 * stay too, together: learning is measured on this phone's encoder and stays with it, and the
 * journal, which carries source fingerprints, is only consistent with the profiles it was written
 * beside. A new phone starts from its model's defaults and measures for itself. The user's
 * settings (`compressor_prefs`, `encoder_experiments`) still travel.
 */
class BackupExclusionTest {

    private fun rules(name: String): String =
        Media3Host.inModule("src/main/res/xml/$name").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun section(xml: String, tag: String): String =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?: throw AssertionError("no <$tag> section")

    private fun exclusion(domain: String, path: String) =
        Regex("""<exclude\s+domain="$domain"\s+path="${Regex.escape(path)}"\s*/>""")

    private val staysOnThisPhone = listOf(
        "file" to "replace_recovery", "file" to "diagnostics",
        "file" to "learning", "sharedpref" to "smart_perceptual_profiles_v4.xml",
        // A device-harness run's folder: rollback copies of a disposable copy of a user's video
        // while a replacement case runs, and the run's own learning journal.
        "file" to "pllab"
    )

    private fun assertExcludesAll(where: String, xml: String) {
        for ((domain, path) in staysOnThisPhone) {
            assertTrue("$where must exclude $domain:$path", exclusion(domain, path).containsMatchIn(xml))
        }
        // Settings are not private to this phone, and nothing else is excluded by accident.
        for (settings in listOf("compressor_prefs.xml", "encoder_experiments.xml")) {
            assertFalse("$where must not exclude $settings", exclusion("sharedpref", settings).containsMatchIn(xml))
        }
    }

    @Test fun privateFilesAreKeptOutOfCloudBackupAndDeviceTransfer() {
        val extraction = rules("data_extraction_rules.xml")
        assertExcludesAll("cloud-backup", section(extraction, "cloud-backup"))
        assertExcludesAll("device-transfer", section(extraction, "device-transfer"))
    }

    @Test fun privateFilesAreKeptOutOfTheBackupOfOlderAndroidVersions() {
        assertExcludesAll("full-backup-content", section(rules("backup_rules.xml"), "full-backup-content"))
    }

    @Test fun theExcludedProfilesAreTheOnesTheEngineWrites() {
        // The rule names a file; it must be the file the profile store opens.
        val engine = Media3Host.inModule("src/main/java/compress/joshattic/us/SmartPerceptualProfileEngine.kt").readText()
        assertTrue(engine.contains("PREFS_NAME = \"smart_perceptual_profiles_v4\""))
        val model = Media3Host.inModule("src/main/java/compress/joshattic/us/BatchCompressorViewModel.kt").readText()
        assertTrue(model.contains("filesDir, \"learning/evidence-v1.jsonl\")"))
    }
}
