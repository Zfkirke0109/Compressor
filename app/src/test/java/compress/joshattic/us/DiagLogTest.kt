package compress.joshattic.us

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision log exists because logcat kept losing the evidence behind a run.
 *
 * batch_1788273016134 is the clean example: it ran 07:30-08:0x and was exported at 08:08. All 64
 * job records survived — the recorder writes those to filesDir — while every line explaining WHY
 * each job ended as it did was gone, because the device's ring buffer held nothing older than
 * 08:07:57. A capture that says what happened but not why cannot settle anything.
 *
 * This pins the bound rather than the plumbing: the cap has to be large enough that a real run
 * fits, and small enough that a runaway cannot fill the user's storage.
 */
class DiagLogTest {

    @Test
    fun theCapFitsAFullSizedRunWithRoomToSpare() {
        // The largest capture in this project is the 219-file batch_1786611119841. Its decision
        // lines run to a few hundred bytes each across a handful of lines per job; 8 MiB leaves
        // roughly an order of magnitude of headroom over that, so no real run is truncated.
        val bytesPerJob = 4 * 400L
        val largestRunJobs = 219L
        assertTrue(
            "cap must fit the largest observed run with headroom",
            DiagLog.MAX_BYTES > bytesPerJob * largestRunJobs * 10
        )
    }

    @Test
    fun theCapStaysSmallEnoughToBeHarmlessOnDevice() {
        // Written to the app's private storage on a phone whose corpus is already tens of GB of
        // video. A diagnostic log must never be the thing that fills it.
        assertTrue("cap must stay modest", DiagLog.MAX_BYTES <= 32L * 1024 * 1024)
    }
}
