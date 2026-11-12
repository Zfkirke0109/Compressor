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
    @Test fun liveStoresShareReservationsAndResetBoundaries() {
        val directory = Files.createTempDirectory("ledger-live-stores").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            val first = LearningEvidenceStore(base, file)
            val second = LearningEvidenceStore(base, directory.resolve("./evidence.jsonl"))
            assertTrue(first.writeObservation("p", "first", evidence()))
            assertFalse(second.writeObservation("p", "duplicate", evidence()))
            assertEquals("first", base.read("p"))
            second.clear()
            assertNull(base.read("p"))
            assertTrue(first.writeObservation("p", "after reset", evidence()))
            assertFalse(second.writeObservation("p", "duplicate after reset", evidence()))
            assertEquals("after reset", base.read("p"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun deduplicationSurvivesJournalReplay() {
        val directory = Files.createTempDirectory("ledger").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore(); val file = directory.resolve("evidence.jsonl")
            LearningEvidenceStore(base, file).writeObservation("p", "one", evidence())
            val replay = file.copyTo(directory.resolve("reopened.jsonl"))
            assertFalse(LearningEvidenceStore(base, replay).writeObservation("p", "two", evidence()))
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
            val replay = file.copyTo(directory.resolve("reopened.jsonl"))
            assertFalse(LearningEvidenceStore(base, replay).writeObservation(key, "two", evidence()))
            assertEquals("one", base.read(key))
        } finally { directory.deleteRecursively() }
    }

    @Test fun anExplicitResetAllowsTheSameMeasurementToTrainAgainAndSurvivesRestart() {
        val directory = Files.createTempDirectory("ledger-reset").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            val store = LearningEvidenceStore(base, file)
            val engine = SmartPerceptualProfileEngine(store)
            assertTrue(store.writeObservation("profile", "before", evidence()))
            engine.resetLearnedState()
            assertEquals("empty", engine.learnedStateIdentity())
            assertTrue(file.readText().contains("\"ledgerEvent\": \"RESET\""))
            assertTrue(LearningEvidenceStore(base, file).writeObservation("profile", "after", evidence()))
            assertFalse(LearningEvidenceStore(base, file).writeObservation("profile", "duplicate", evidence()))
            assertEquals("after", base.read("profile"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun aFailedDurableResetIsReportedAndLeavesProfilesIntact() {
        val directory = Files.createTempDirectory("ledger-reset-failure").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            base.write("profile", "keep")
            val store = LearningEvidenceStore(base, directory) // A directory cannot be an append-only journal.
            try {
                SmartPerceptualProfileEngine(store).resetLearnedState()
                fail("Reset must not claim success without a durable RESET record")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("profiles were kept unchanged"))
            }
            assertEquals("keep", base.read("profile"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun anInterruptedResetCannotSilentlyReenableTrainingAfterRestart() {
        val directory = Files.createTempDirectory("ledger-reset-interrupted").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            val failing = object : SmartPerceptualProfileEngine.ProfileStore by base {
                override fun clear() { error("profile persistence failed") }
            }
            val store = LearningEvidenceStore(failing, file)
            assertTrue(store.writeObservation("profile", "before", evidence()))
            try { store.clear(); fail("Failed persistence must propagate") }
            catch (expected: IllegalStateException) { assertEquals("profile persistence failed", expected.message) }
            val after = evidence().copy(configId = "new-operating-point")
            assertFalse(store.writeObservation("profile", "unsafe", after))
            val replay = file.copyTo(directory.resolve("reopened.jsonl"))
            assertFalse(LearningEvidenceStore(base, replay).writeObservation("profile", "unsafe", after))
            assertEquals("before", base.read("profile"))
        } finally { directory.deleteRecursively() }
    }

    // ---- Applied means on disk ----------------------------------------------------------------

    /** A store whose values take effect in memory and whose durable write reports [durable]. */
    private fun store(base: SmartPerceptualProfileEngine.ProfileStore, durable: Boolean, failing: Boolean = false) =
        object : SmartPerceptualProfileEngine.ProfileStore by base {
            override fun writeDurably(key: String, value: String): Boolean {
                if (failing) error("storage failed")
                base.write(key, value)
                return durable
            }
        }

    @Test fun anUpdateIsRecordedAsAppliedOnlyOnceTheAggregateWriteIsOnDisk() {
        val directory = Files.createTempDirectory("ledger-durable").toFile()
        try {
            // The ordinary case first: reserved, then applied with the value that landed.
            val healthy = directory.resolve("healthy.jsonl")
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            assertTrue(LearningEvidenceStore(store(base, durable = true), healthy).writeObservation("p", "one", evidence()))
            assertEquals(listOf("RESERVED", "APPLIED"), events(healthy))
            assertTrue(healthy.readLines().last().contains("\"after\": \"one\""))

            // SharedPreferences.apply() returned before the value was on disk and the ledger wrote
            // APPLIED anyway. A write the store cannot confirm is recorded as exactly that.
            val unconfirmed = directory.resolve("unconfirmed.jsonl")
            val memoryOnly = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val ledger = LearningEvidenceStore(store(memoryOnly, durable = false), unconfirmed)
            // The value is in effect in this process, so the caller is told it took.
            assertTrue(ledger.writeObservation("p", "one", evidence()))
            assertEquals("one", memoryOnly.read("p"))
            assertEquals(listOf("RESERVED", "APPLY_UNCONFIRMED"), events(unconfirmed))
            assertFalse(unconfirmed.readText().contains("\"effectApplied\": true"))
            // Nothing more is trained on a state the ledger cannot vouch for.
            assertFalse(ledger.writeObservation("p", "two", evidence().copy(configId = "another")))
            assertEquals("one", memoryOnly.read("p"))
            // After a restart the measurement is still reserved, as any RESERVED without APPLIED is.
            val reopened = unconfirmed.copyTo(directory.resolve("reopened.jsonl"))
            assertFalse(LearningEvidenceStore(memoryOnly, reopened).writeObservation("p", "again", evidence()))

            // A write that fails outright changed nothing, and says so.
            val failed = directory.resolve("failed.jsonl")
            val untouched = SmartPerceptualProfileEngine.InMemoryProfileStore()
            assertFalse(LearningEvidenceStore(store(untouched, durable = false, failing = true), failed).writeObservation("p", "one", evidence()))
            assertNull(untouched.read("p"))
            assertEquals(listOf("RESERVED", "APPLY_UNCONFIRMED"), events(failed))
        } finally { directory.deleteRecursively() }
    }

    @Test fun theRecordingStorePassesDurabilityThroughAndStillReportsTheChange() {
        val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
        val reported = ArrayList<Triple<String, String?, String>>()
        val durable = RecordingProfileStore(store(base, durable = true)) { k, before, after -> reported += Triple(k, before, after) }
        assertTrue(durable.writeDurably("p", "one"))
        val memoryOnly = RecordingProfileStore(store(base, durable = false)) { k, before, after -> reported += Triple(k, before, after) }
        assertFalse(memoryOnly.writeDurably("p", "two"))
        assertEquals(listOf(Triple("p", null, "one"), Triple("p", "one", "two")), reported)
        // A write that never happened is not reported as a change.
        val failing = RecordingProfileStore(store(base, durable = false, failing = true)) { k, before, after -> reported += Triple(k, before, after) }
        try { failing.writeDurably("p", "three"); fail("the failure must reach the ledger") } catch (expected: IllegalStateException) { }
        assertEquals(2, reported.size)
        assertEquals("two", base.read("p"))
    }

    // ---- A journal that can no longer be appended to -------------------------------------------

    private fun events(journal: java.io.File): List<String> =
        journal.readLines().map { checkNotNull(Regex("\"ledgerEvent\": \"([A-Z_]+)\"").find(it)).groupValues[1] }

    private fun nth(i: Int) = evidence().copy(configId = "config-$i")

    @Test fun aFullJournalRotatesAndStillRefusesWhatHasAlreadyTrained() {
        val directory = Files.createTempDirectory("ledger-rotation").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            // Room for a few observations (each writes two lines of several hundred bytes) on top
            // of the short line a rotation carries for every one that has trained.
            val bound = 16_000L
            val store = LearningEvidenceStore(base, file, maxBytes = bound)
            val trained = 40
            for (i in 0 until trained) {
                assertTrue("observation $i was refused", store.writeObservation("profile", "value-$i", nth(i)))
                assertTrue("the journal grew past its bound at $i", file.length() <= bound)
            }
            // It filled and was rotated, more than once; the aggregate was never touched by that.
            assertEquals("value-${trained - 1}", base.read("profile"))
            assertEquals("ROTATED", events(file).first())
            assertTrue(java.io.File(directory, "evidence.jsonl" + LearningEvidenceStore.ARCHIVE_SUFFIX).isFile)
            assertFalse(java.io.File(directory, "evidence.jsonl.rotating").exists())
            // Every measurement that trained is still refused: in this process, and after a restart.
            for (i in 0 until trained) assertFalse("observation $i trained twice", store.writeObservation("profile", "again", nth(i)))
            val reopened = LearningEvidenceStore(base, file.copyTo(directory.resolve("reopened.jsonl")), maxBytes = bound)
            for (i in 0 until trained) assertFalse("observation $i trained twice after a restart", reopened.writeObservation("profile", "again", nth(i)))
            assertEquals("value-${trained - 1}", base.read("profile"))
            // And a new one still trains.
            assertTrue(reopened.writeObservation("profile", "new", nth(trained)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun aJournalFullOfLiveReservationsPausesLearningAndForgetsNothing() {
        val directory = Files.createTempDirectory("ledger-full").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            // Too small for the reservations a rotation would have to carry.
            val store = LearningEvidenceStore(base, file, maxBytes = 2_000L)
            var accepted = 0
            while (store.writeObservation("profile", "value-$accepted", nth(accepted))) accepted++
            assertTrue("nothing fitted at all", accepted in 1..20)
            val before = file.readText()
            assertEquals("value-${accepted - 1}", base.read("profile"))
            // Paused, with the journal as it was and no half-made replacement left behind.
            assertFalse(store.writeObservation("profile", "more", nth(1000)))
            assertEquals(before, file.readText())
            assertFalse(java.io.File(directory, "evidence.jsonl.rotating").exists())
            for (i in 0 until accepted) assertFalse(LearningEvidenceStore(base, file.copyTo(directory.resolve("copy-$i.jsonl")), maxBytes = 2_000L).writeObservation("profile", "again", nth(i)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun anExplicitResetIsTheWayOutOfEveryJournalThatCannotBeAppendedTo() {
        val directory = Files.createTempDirectory("ledger-recovery").toFile()
        try {
            // 1. Full beyond what a rotation can free.
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val full = directory.resolve("full.jsonl")
            val store = LearningEvidenceStore(base, full, maxBytes = 2_000L)
            var accepted = 0
            while (store.writeObservation("profile", "value-$accepted", nth(accepted))) accepted++
            val fullText = full.readText()
            store.clear()
            assertNull(base.read("profile"))
            assertEquals(listOf("RESET_STARTED", "RESET"), events(full))
            assertEquals(fullText, java.io.File(directory, "full.jsonl" + LearningEvidenceStore.ARCHIVE_SUFFIX).readText())
            // The reset is a reset: what trained before may train again, once.
            assertTrue(store.writeObservation("profile", "after", nth(0)))
            assertFalse(store.writeObservation("profile", "duplicate", nth(0)))
            assertTrue(LearningEvidenceStore(base, full.copyTo(directory.resolve("full-reopened.jsonl")), maxBytes = 2_000L)
                .writeObservation("profile", "next", nth(1)))

            // 2. A reset that never finished: paused across a restart, until the next explicit reset.
            val interrupted = directory.resolve("interrupted.jsonl")
            interrupted.writeText(JsonText.render(mapOf("ledgerVersion" to 1, "ledgerEvent" to "RESERVED",
                "evidenceId" to evidence().id, "profileKey" to "profile")).replace("\n", "") + "\n" +
                JsonText.render(mapOf("ledgerVersion" to 1, "ledgerEvent" to "RESET_STARTED", "timestampMs" to 1L)).replace("\n", "") + "\n")
            val second = SmartPerceptualProfileEngine.InMemoryProfileStore().also { it.write("profile", "kept") }
            val paused = LearningEvidenceStore(second, interrupted)
            assertFalse(paused.writeObservation("profile", "unsafe", nth(5)))
            assertEquals("kept", second.read("profile"))
            paused.clear()
            assertNull(second.read("profile"))
            assertTrue(paused.writeObservation("profile", "trained", nth(5)))
            assertTrue(LearningEvidenceStore(second, interrupted.copyTo(directory.resolve("interrupted-reopened.jsonl")))
                .writeObservation("profile", "trained again", nth(6)))

            // 3. A line cut off by a killed write.
            val cut = directory.resolve("cut.jsonl")
            cut.writeText(JsonText.render(mapOf("ledgerVersion" to 1, "ledgerEvent" to "RESERVED",
                "evidenceId" to evidence().id, "profileKey" to "profile")).replace("\n", "") + "\n" + "{\"ledgerVersion\": 1, \"ledgerEv")
            val third = SmartPerceptualProfileEngine.InMemoryProfileStore().also { it.write("profile", "kept") }
            val damaged = LearningEvidenceStore(third, cut)
            assertFalse(damaged.writeObservation("profile", "unsafe", nth(7)))
            assertEquals("kept", third.read("profile"))
            damaged.clear()
            assertEquals(listOf("RESET_STARTED", "RESET"), events(cut))
            assertTrue(damaged.writeObservation("profile", "trained", nth(7)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun aRotationThatNeverFinishedLeavesTheJournalInCharge() {
        val directory = Files.createTempDirectory("ledger-stale-rotation").toFile()
        try {
            val base = SmartPerceptualProfileEngine.InMemoryProfileStore()
            val file = directory.resolve("evidence.jsonl")
            assertTrue(LearningEvidenceStore(base, file).writeObservation("profile", "one", evidence()))
            // The process died after writing the replacement and before the rename.
            val journal = file.copyTo(directory.resolve("restarted.jsonl"))
            val stale = java.io.File(directory, "restarted.jsonl.rotating").also { it.writeText("{\"ledgerVersion\": 1, \"ledgerEvent\": \"ROTATED\"}\n") }
            val store = LearningEvidenceStore(base, journal)
            assertFalse(stale.exists())
            assertFalse(store.writeObservation("profile", "duplicate", evidence()))
            assertTrue(store.writeObservation("profile", "two", nth(1)))
        } finally { directory.deleteRecursively() }
    }
}
