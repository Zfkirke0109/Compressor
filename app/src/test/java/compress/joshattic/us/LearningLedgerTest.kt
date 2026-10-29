package compress.joshattic.us

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LearningLedgerTest {
    private fun evidence() = LearningObservation("a".repeat(64), "full-sha256", "config", listOf("100-200"),
        "probe", LearningObservation.Kind.VISUAL_REJECT, "build1", "encoder")

    @Test fun repeatedSourceConfigurationWindowCannotUpdateTheAggregateTwice() {
        val directory = Files.createTempDirectory("ledger").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val store = LearningEvidenceStore(base, directory.resolve("evidence.jsonl"))
            assertTrue(store.writeObservation("profile", "first", evidence()))
            assertFalse(store.writeObservation("profile", "second", evidence()))
            assertEquals("first", base.read("profile"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun deduplicationSurvivesProcessRestart() {
        val directory = Files.createTempDirectory("ledger").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore(); val file = directory.resolve("evidence.jsonl")
            LearningEvidenceStore(base, file).writeObservation("p", "one", evidence())
            assertFalse(LearningEvidenceStore(base, file).writeObservation("p", "two", evidence()))
            assertEquals("one", base.read("p"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun unboundPipelineAndIncompatibleEvidenceNeverTrain() {
        val directory = Files.createTempDirectory("ledger").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val store = LearningEvidenceStore(base, directory.resolve("evidence.jsonl"))
            for (o in listOf(null, evidence().copy(kind=LearningObservation.Kind.PIPELINE),
                evidence().copy(policyEpoch="pre-pairing-fix"), evidence().copy(sourceId="unknown"))) {
                assertFalse(store.writeObservation("p", "invalid", o))
            }
            assertNull(base.read("p"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun buildsAndConflictingRepeatLabelsDoNotCreateANewIndependentObservation() {
        val one = evidence()
        assertEquals(one.id, one.copy(appCommit="new-build",kind=LearningObservation.Kind.VISUAL_PASS).id)
        assertNotEquals(one.id, one.copy(configId="different-operating-point").id)
        assertNotEquals(one.id, one.copy(policyEpoch="next-measurement-policy").id)
    }

    @Test fun aMigratedLedgerKeepsDeviceProfilesSeparateWithoutResettingTheS23() {
        val directory = Files.createTempDirectory("ledger-migration").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            val s23 = "samsung|sm-s918u1|37|video/hevc"
            val s26 = "samsung|sm-s948u|37|video/hevc"
            // Existing v1 rows already carry the profile, so no erasure or id rewrite is needed.
            file.writeText(JsonText.render(mapOf("ledgerVersion" to 1, "ledgerEvent" to "RESERVED",
                "evidenceId" to evidence().id, "profileKey" to s23)).replace("\n", "") + "\n")
            base.write(s23, "s23-history")
            val migrated = LearningEvidenceStore(base, file)
            assertFalse(migrated.writeObservation(s23, "duplicate", evidence()))
            assertTrue(migrated.writeObservation(s26, "s26-measurement", evidence()))
            assertEquals("s23-history", base.read(s23))
            assertEquals("s26-measurement", base.read(s26))
            assertFalse(LearningEvidenceStore(base, file).writeObservation(s26, "duplicate", evidence()))
        } finally { directory.deleteRecursively() }
    }

    @Test fun escapedProfileKeysAlsoDeduplicateAfterRestart() {
        val directory = Files.createTempDirectory("ledger-escaping").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            val key = "profile\"key\\with\ncontrols"
            assertTrue(LearningEvidenceStore(base, file).writeObservation(key, "one", evidence()))
            assertFalse(LearningEvidenceStore(base, file).writeObservation(key, "two", evidence()))
            assertEquals("one", base.read(key))
        } finally { directory.deleteRecursively() }
    }
}
