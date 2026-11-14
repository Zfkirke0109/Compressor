package compress.joshattic.us

import compress.joshattic.us.ForegroundStartStopGate.State
import compress.joshattic.us.ForegroundStartStopGate.StopAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ordering that build pr44-b158 got wrong on the S23 Ultra (Android 17).
 *
 * batch_1790259773632 was one HDR PQ HEVC file that Perceptually Lossless keeps exact. It ran from
 * session_start to session_summary in 42 ms. The batch started the foreground service on entry
 * and stopped it in its finally block, so stopService overtook startForeground. The platform
 * crashes an app for that, and a new pid appeared about 2 s later.
 */
class ForegroundStartStopGateTest {

    @Test
    fun aStopBeforeTheServiceReachesTheForegroundIsDeferredNotSent() {
        // The exact b158 sequence: start, then stop before onStartCommand has run.
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        assertEquals(StopAction.DEFER_UNTIL_FOREGROUND, gate.onStopRequested())
        assertTrue(gate.isStopPending)
    }

    @Test
    fun theDeferredStopIsCarriedOutOnceStartForegroundHasRun() {
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        gate.onStopRequested()
        assertTrue("service must stop itself after startForeground", gate.onForegroundEntered())
        assertEquals(State.IDLE, gate.currentState)
        assertFalse(gate.isStopPending)
    }

    @Test
    fun aNormalLongBatchStillStopsDirectly() {
        // Every batch before b158: the service was foreground long before the batch ended.
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        assertFalse(gate.onForegroundEntered())
        assertEquals(State.FOREGROUND, gate.currentState)
        assertEquals(StopAction.STOP_NOW, gate.onStopRequested())
        assertEquals(State.IDLE, gate.currentState)
    }

    @Test
    fun stoppingWithNothingRunningDoesNothing() {
        assertEquals(StopAction.NONE, ForegroundStartStopGate().onStopRequested())
    }

    @Test
    fun aRepeatedStopAfterTheFirstIsANoOp() {
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        gate.onForegroundEntered()
        assertEquals(StopAction.STOP_NOW, gate.onStopRequested())
        assertEquals(StopAction.NONE, gate.onStopRequested())
    }

    @Test
    fun aStartThatThrowsLeavesNothingPending() {
        // e.g. ForegroundServiceStartNotAllowedException from startForegroundService itself.
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        gate.onStartFailed()
        assertEquals(StopAction.NONE, gate.onStopRequested())
        assertFalse(gate.isStopPending)
    }

    @Test
    fun aFailedStartForegroundClearsThePendingStop() {
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        gate.onStopRequested()
        gate.onForegroundFailed()
        assertEquals(State.IDLE, gate.currentState)
        assertFalse(gate.isStopPending)
    }

    @Test
    fun aNewStartCancelsAStopStillWaitingOnTheOldOne() {
        // Back-to-back runs: the second batch wants the service up, so the first batch's stop
        // must not take it down once it reaches the foreground.
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        gate.onStopRequested()
        gate.onStartRequested()
        assertFalse(gate.onForegroundEntered())
        assertEquals(State.FOREGROUND, gate.currentState)
    }

    @Test
    fun anOldInstanceBeingDestroyedDoesNotHideANewerPendingStart() {
        // Batch 1 stops the service, batch 2 starts it again, and then the old instance's
        // onDestroy arrives. If that cleared START_REQUESTED, batch 2's stop would return NONE
        // and leave the new instance stuck in the foreground with its notification showing.
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        gate.onForegroundEntered()
        assertEquals(StopAction.STOP_NOW, gate.onStopRequested())
        gate.onStartRequested()
        gate.onServiceDestroyed()
        assertEquals(State.START_REQUESTED, gate.currentState)
        assertEquals(StopAction.DEFER_UNTIL_FOREGROUND, gate.onStopRequested())
        assertTrue(gate.onForegroundEntered())
    }

    @Test
    fun aPlatformStopOfAForegroundServiceReturnsTheGateToIdle() {
        // The foreground-service timeout path calls stopSelf; the batch's later stop is then a no-op.
        val gate = ForegroundStartStopGate()
        gate.onStartRequested()
        gate.onForegroundEntered()
        gate.onServiceDestroyed()
        assertEquals(StopAction.NONE, gate.onStopRequested())
    }
}
