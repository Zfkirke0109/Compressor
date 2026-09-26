package compress.joshattic.us

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Orchestration of one item through ItemPipeline with fake stages: the phases the row passes
 * through, and that nothing but a terminal state completes it. Replays the b169 recording (row
 * 110, job_d127463b57d6): Transformer reached 100 % and the row read "Compressing … 100%" while the
 * metadata remux and 16 s of pixel certification were still to run.
 */
class ItemPipelineTest {

    private class ListBoard(items: List<BatchVideoItem>) : ItemBoard {
        val items = items.toMutableList()
        override fun item(index: Int): BatchVideoItem? = items.getOrNull(index)
        override fun update(index: Int, transform: (BatchVideoItem) -> BatchVideoItem) {
            items[index] = transform(items[index])
        }
    }

    private fun item(name: String = "row110.mp4") = BatchVideoItem(
        // Never dereferenced: the pipeline and the model only compare tokens and fields.
        sourceUri = TestUris.placeholder,
        originalName = name,
        originalSize = 322_388_270L,
        originalWidth = 1080,
        originalHeight = 1080,
        originalBitrate = 3_057_920,
        originalAudioBitrate = 128_000,
        originalFps = 30f,
        durationMs = 843_418L
    )

    private val token = AttemptToken("batch_1790428395761", 0, 1)

    private fun board(): ListBoard =
        ListBoard(listOf(item(), item("row111.mp4"))).also { b ->
            b.update(0) { ItemProgressModel.begin(it, token, provisionalEstimateBytes = 317_158_000L) }
        }

    /** Records every row state the pipeline leaves between stages. */
    private open inner class Stages(val board: ListBoard) : ItemStages {
        val seen = mutableListOf<BatchVideoItem>()
        var finalized = false
        override suspend fun plan(phases: PhaseReporter): Boolean {
            phases.enter(ItemPhase.PROBING)
            phases.planResolved(247_616_391L)
            return true
        }
        override suspend fun produce(phases: PhaseReporter): Boolean {
            // Media3 progress, including the 100 % it reports at the end of the export.
            phases.encodeProgress(0.90f, 215_200_000L)
            phases.encodeProgress(1.0f, 258_300_000L)
            seen += board.items[0]
            phases.encodeFinished()
            seen += board.items[0]
            phases.candidateReady(246_118_100L)
            seen += board.items[0]
            return true
        }
        override suspend fun verify(phases: PhaseReporter): Boolean {
            seen += board.items[0]
            phases.enter(ItemPhase.CERTIFYING)
            phases.certifyStep(0, 3)
            phases.certifyStep(1, 3)
            seen += board.items[0]
            return true
        }
        override suspend fun finalize(phases: PhaseReporter) {
            seen += board.items[0]
            finalized = true
            board.update(0) { it.copy(status = BatchItemStatus.Done, progress = 1f, terminalResult = BatchTerminalResult.TRANSCODED_SMALLER, outputSize = 246_118_100L) }
        }
    }

    @Test
    fun anEncodeAtOneHundredPercentIsNotACompletedItem() = runBlocking {
        val b = board()
        val stages = Stages(b)
        assertEquals(ItemPipeline.Result.TERMINAL, ItemPipeline(b).run(token, stages))

        val atFullEncode = stages.seen[0]
        assertEquals(ItemPhase.ENCODING, atFullEncode.phase)
        // Media3's 1.0 is shown as at most 99 % of the ENCODE, and the item is not complete.
        assertEquals(ItemProgressModel.MAX_RUNNING_FRACTION, atFullEncode.phaseFraction!!, 0f)
        assertEquals("Encoding 99%", ItemProgressModel.describe(atFullEncode))
        assertFalse(ItemProgressModel.isTerminal(atFullEncode))
        assertEquals(0f, ItemProgressModel.batchFraction(listOf(atFullEncode)), 0f)

        // After the export: finalizing, indeterminate, and the live file length is no longer shown.
        val finalizing = stages.seen[1]
        assertEquals(ItemPhase.FINALIZING, finalizing.phase)
        assertNull(finalizing.phaseFraction)
        assertEquals(0L, finalizing.currentOutputSize)
        assertEquals("Finalizing output", ItemProgressModel.describe(finalizing))

        // The closed file's size is a candidate, not an accepted output.
        val candidate = stages.seen[2]
        assertEquals(246_118_100L, candidate.candidateOutputSize)
        assertEquals(0L, candidate.outputSize)

        assertEquals(ItemPhase.VERIFYING, stages.seen[3].phase)
        assertEquals("Certifying pixels 1 of 3 windows", ItemProgressModel.describe(stages.seen[4]))
        assertEquals(ItemPhase.SAVING, stages.seen[5].phase)
        assertTrue(stages.finalized)
        assertEquals(0.5f, ItemProgressModel.batchFraction(b.items), 0f)
    }

    @Test
    fun theEstimateIsProvisionalUntilThePlanResolves() = runBlocking {
        val b = board()
        assertTrue(b.items[0].estimateIsProvisional)
        assertEquals(317_158_000L, b.items[0].targetOutputSize)
        ItemPipeline(b).run(token, Stages(b))
        assertFalse(b.items[0].estimateIsProvisional)
        assertEquals(247_616_391L, b.items[0].targetOutputSize)
    }

    @Test
    fun unavailableMedia3ProgressIsIndeterminateNotZero() {
        val b = board()
        val phases = PhaseReporter(b, token)
        phases.enter(ItemPhase.ENCODING)
        phases.encodeProgress(0.4f, 10L)
        phases.encodeProgress(null, 20L)
        assertNull(b.items[0].phaseFraction)
        assertEquals("Encoding", ItemProgressModel.describe(b.items[0]))
    }

    @Test
    fun aSkipInVerificationEndsTheItemWithoutSaving() = runBlocking {
        val b = board()
        val stages = object : Stages(b) {
            override suspend fun verify(phases: PhaseReporter): Boolean {
                // Certification measured the output below the bar: original kept, candidate gone.
                b.update(0) {
                    it.copy(status = BatchItemStatus.Skipped, progress = 1f, candidateOutputSize = 0L,
                        outputPath = null, outputSize = 0L, terminalResult = BatchTerminalResult.SKIPPED_WOULD_DEGRADE)
                }
                return false
            }
        }
        assertEquals(ItemPipeline.Result.TERMINAL, ItemPipeline(b).run(token, stages))
        assertFalse(stages.finalized)
        val row = b.items[0]
        assertEquals(BatchItemStatus.Skipped, row.status)
        assertNull(row.outputPath)
        assertEquals(0L, row.outputSize)
        // A skipped item is complete without being a compression.
        assertTrue(ItemProgressModel.isTerminal(row))
        assertEquals(false, row.terminalResult?.countsAsRealCompression)
    }

    @Test
    fun aStageThatEndsWithoutATerminalStateIsReported() = runBlocking {
        val b = board()
        val stages = object : Stages(b) {
            override suspend fun produce(phases: PhaseReporter): Boolean = false
        }
        assertEquals(ItemPipeline.Result.ENDED_WITHOUT_TERMINAL, ItemPipeline(b).run(token, stages))
    }

    @Test
    fun cancellationPropagatesAndLateCallbacksCannotReviveTheRow() = runBlocking {
        val b = board()
        var late: PhaseReporter? = null
        val stages = object : Stages(b) {
            override suspend fun produce(phases: PhaseReporter): Boolean {
                late = phases
                phases.encodeProgress(0.5f, 100L)
                throw CancellationException("user cancelled")
            }
        }
        try {
            ItemPipeline(b).run(token, stages)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
        // The batch marks the row cancelled (publishBatchCancelled); a Transformer tick that was
        // already queued then arrives.
        b.update(0) { it.copy(status = BatchItemStatus.Cancelled, terminalResult = BatchTerminalResult.CANCELLED) }
        late!!.encodeProgress(0.99f, 999L)
        late!!.message("Writing 999 MiB")
        late!!.enter(ItemPhase.CERTIFYING)
        val row = b.items[0]
        assertEquals(BatchItemStatus.Cancelled, row.status)
        assertEquals(0.5f, row.phaseFraction!!, 0f)
        assertEquals(100L, row.currentOutputSize)
        assertEquals(ItemPhase.ENCODING, row.phase)
        assertFalse(row.message == "Writing 999 MiB")
    }

    @Test
    fun aRetryOwnsTheRowAndTheFailedAttemptsCallbacksAreIgnored() = runBlocking {
        val b = board()
        val retryToken = AttemptToken(token.batchId, 0, 2)
        var current = PhaseReporter(b, token)
        var first: PhaseReporter? = null
        val stages = object : Stages(b) {
            override fun currentPhases(initial: PhaseReporter): PhaseReporter = current
            override suspend fun verify(phases: PhaseReporter): Boolean {
                first = phases
                // Certification failed on measured windows; the opt-in retry takes over.
                b.update(0) { ItemProgressModel.handOver(it, token, retryToken) }
                current = PhaseReporter(b, retryToken)
                current.enter(ItemPhase.ENCODING)
                current.encodeProgress(0.3f, 50L)
                return true
            }
        }
        ItemPipeline(b).run(token, stages)
        // The first attempt's export poller fires once more after the handover.
        first!!.encodeProgress(0.97f, 258_300_000L)
        first!!.message("stale")
        val row = b.items[0]
        assertEquals(retryToken, row.attempt)
        // The pipeline's SAVING transition went to the attempt that owns the row.
        assertEquals(BatchItemStatus.Done, row.status)
        assertFalse(row.message == "stale")
        assertTrue(stages.finalized)
    }

    @Test
    fun anItemOfAnotherBatchOrIndexIsNotTouched() {
        val b = board()
        val other = PhaseReporter(b, AttemptToken("batch_other", 0, 1))
        other.enter(ItemPhase.CERTIFYING)
        assertEquals(ItemPhase.PREPARING, b.items[0].phase)
        // The second row never started: nothing can move it.
        PhaseReporter(b, AttemptToken(token.batchId, 1, 1)).enter(ItemPhase.ENCODING)
        assertEquals(ItemPhase.QUEUED, b.items[1].phase)
    }

    @Test
    fun certificationStepsAreClampedToThePlannedWindows() {
        val b = board()
        val phases = PhaseReporter(b, token)
        phases.enter(ItemPhase.CERTIFYING)
        phases.certifyStep(5, 3)
        assertEquals(3, b.items[0].phaseStep)
        phases.certifyStep(1, 0)
        assertEquals(3, b.items[0].phaseStep)
    }
}
