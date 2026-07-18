package compress.joshattic.us

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Which foreground-service type a batch may declare on a given platform level.
 *
 * Pure and Android-instance-free (the `ServiceInfo`/`Build.VERSION_CODES` values it uses are
 * compile-time int constants) so the threshold is unit-testable on the JVM. It is extracted from
 * [BatchForegroundService] precisely because getting it wrong is silent: an unsupported type does
 * not fail loudly at build time, it fails at `startForeground` on real devices — leaving a long
 * batch unprotected, which is the failure PERF-001 exists to prevent.
 *
 * Threshold history (do not "simplify" these bounds):
 *  - `mediaProcessing` and its FOREGROUND_SERVICE_MEDIA_PROCESSING permission FIRST EXIST on
 *    **API 35** (Android 15). An earlier revision guarded at `>= 34`; on Android 14 that requests a
 *    type whose permission the OS does not recognize and the call can be rejected.
 *  - `dataSync` is valid from API 29 and is the correct choice for API 29-34.
 *  - Below API 29 `startForeground` takes no type at all.
 */
object ForegroundServiceTypePolicy {

    /** First platform level where mediaProcessing (and its permission) exists. */
    const val MEDIA_PROCESSING_MIN_SDK = 35

    /**
     * @param sdkInt the running platform level (`Build.VERSION.SDK_INT` in production).
     * @return the type to pass to `startForeground`, or 0 when the platform takes no type.
     */
    fun typeForSdk(sdkInt: Int): Int = when {
        sdkInt >= MEDIA_PROCESSING_MIN_SDK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        sdkInt >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /** True when this platform expects a typed `startForeground(id, notification, type)` call. */
    fun requiresTypedStartForeground(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q
}
