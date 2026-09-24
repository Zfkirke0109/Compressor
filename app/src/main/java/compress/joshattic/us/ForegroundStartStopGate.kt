package compress.joshattic.us

/**
 * Orders stop requests against the platform's `startForegroundService()` contract.
 *
 * The contract: once `Context.startForegroundService()` has been called, the service MUST reach
 * `Service.startForeground()`. If it is stopped first, ActivityManager does not quietly drop the
 * request. `ActiveServices.bringDownServiceLocked` sees the outstanding foreground requirement and
 * posts SERVICE_FOREGROUND_CRASH_MSG, which kills the app with "Context.startForegroundService() did
 * not then call Service.startForeground()". This is long-standing platform behaviour, not
 * something new in Android 17; this app targets SDK 36, so no Android 17 target-SDK change applies.
 *
 * Why it only surfaced now: batch_1790259773632 (build pr44-b158, Android 17) was one HDR PQ HEVC
 * file that Perceptually Lossless keeps exact. The keep-original fast path finished in 30 ms, and
 * the whole batch took 42 ms from session_start (09:22:53.640) to session_summary (.682). The batch
 * called `BatchForegroundService.start` on entry and `stop` in its finally block. Both ran on the
 * main thread, and that thread must process the service's onCreate/onStartCommand, so `stopService`
 * reached ActivityManager before `startForeground` could run. The process was gone about 2 s later:
 * a new pid (24448) logged its codec dump at 09:22:55.787. The user repeated the run and the same
 * thing happened (pid 24448 -> 25202). Every earlier batch encoded for seconds or longer, so the
 * service had always started before the stop arrived.
 *
 * This gate never lets a stop overtake a start that has not reached the foreground. A stop that
 * arrives early is recorded and carried out by the service itself, straight after it calls
 * `startForeground`, which satisfies the contract. Stops that arrive later are handled as before.
 *
 * Pure and synchronised, with no Android dependencies, so the ordering is unit-testable. Every
 * production call arrives on the main thread: the batch coroutine runs on Dispatchers.Main,
 * `onCleared` runs on main, and so do the service callbacks. The lock covers any caller that does not.
 */
class ForegroundStartStopGate {

    enum class State {
        /** No service is running or requested. */
        IDLE,

        /** `startForegroundService` was called and `startForeground` has not run yet. */
        START_REQUESTED,

        /** The service called `startForeground` and can be stopped directly. */
        FOREGROUND
    }

    enum class StopAction {
        /** Call `stopService` now. The foreground contract is already satisfied. */
        STOP_NOW,

        /**
         * Do NOT call `stopService`. The service stops itself right after it calls `startForeground`.
         * Stopping now is exactly what crashes the app.
         */
        DEFER_UNTIL_FOREGROUND,

        /** Nothing is running and nothing was requested. */
        NONE
    }

    private var state = State.IDLE
    private var stopPending = false

    @get:Synchronized
    val currentState: State
        get() = state

    @get:Synchronized
    val isStopPending: Boolean
        get() = stopPending

    /**
     * Call immediately BEFORE `startForegroundService`. A new start cancels any stop still waiting
     * on the previous one, because the caller wants the service running again.
     */
    @Synchronized
    fun onStartRequested() {
        state = State.START_REQUESTED
        stopPending = false
    }

    /** `startForegroundService` itself threw, so no service is coming and no contract is pending. */
    @Synchronized
    fun onStartFailed() {
        state = State.IDLE
        stopPending = false
    }

    @Synchronized
    fun onStopRequested(): StopAction = when (state) {
        State.IDLE -> StopAction.NONE
        State.START_REQUESTED -> {
            stopPending = true
            StopAction.DEFER_UNTIL_FOREGROUND
        }
        State.FOREGROUND -> {
            state = State.IDLE
            StopAction.STOP_NOW
        }
    }

    /**
     * Call right after `startForeground` succeeds. Returns true when a stop arrived while the
     * start was in flight. The service must then take itself out of the foreground and stop, and
     * the contract is satisfied because `startForeground` has already run.
     */
    @Synchronized
    fun onForegroundEntered(): Boolean {
        if (stopPending) {
            stopPending = false
            state = State.IDLE
            return true
        }
        state = State.FOREGROUND
        return false
    }

    /** `startForeground` threw, so the service stops itself and there is nothing left to stop. */
    @Synchronized
    fun onForegroundFailed() {
        state = State.IDLE
        stopPending = false
    }

    /**
     * The service was destroyed, whether by the platform, by a foreground-service timeout, or by
     * a stop this gate allowed.
     *
     * A START_REQUESTED state is deliberately left alone. It belongs to a NEWER start, one made
     * after this instance was told to stop, whose own `onStartCommand` has not run yet. Clearing
     * it would let the next stop return NONE while that start is still in flight, which would
     * leave the service stuck in the foreground with its notification showing.
     */
    @Synchronized
    fun onServiceDestroyed() {
        if (state == State.FOREGROUND) state = State.IDLE
    }
}
