package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for a real defect found in PERF-001 review: the mediaProcessing foreground-service
 * type was originally gated at `SDK_INT >= 34`, but that type and its
 * FOREGROUND_SERVICE_MEDIA_PROCESSING permission FIRST EXIST on **API 35** (Android 15). On Android 14
 * the guard would request a type whose permission the OS does not recognize — which fails at
 * `startForeground` on a real device, not at build time, leaving a long batch unprotected.
 *
 * These assertions use the literal platform values on purpose: they pin the actual wire values the OS
 * sees (0x2000 mediaProcessing, 0x1 dataSync), so the boundary cannot drift even if a constant is
 * mis-swapped. Device-confirmed: an Android 16 (API 36) run reported `types=0x00002000`.
 */
class ForegroundServiceTypePolicyTest {

    private val mediaProcessing = 0x00002000 // ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    private val dataSync = 0x00000001        // ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private val noType = 0

    @Test
    fun android14UsesDataSyncNotMediaProcessing() {
        // THE regression guard: API 34 must NOT get mediaProcessing.
        assertEquals(dataSync, ForegroundServiceTypePolicy.typeForSdk(34))
    }

    @Test
    fun mediaProcessingStartsExactlyAtApi35() {
        assertEquals(dataSync, ForegroundServiceTypePolicy.typeForSdk(34))
        assertEquals(mediaProcessing, ForegroundServiceTypePolicy.typeForSdk(35))
        assertEquals(mediaProcessing, ForegroundServiceTypePolicy.typeForSdk(36))
        assertEquals(35, ForegroundServiceTypePolicy.MEDIA_PROCESSING_MIN_SDK)
    }

    @Test
    fun dataSyncCoversApi29Through34() {
        (29..34).forEach { sdk ->
            assertEquals("sdk $sdk should use dataSync", dataSync, ForegroundServiceTypePolicy.typeForSdk(sdk))
        }
    }

    @Test
    fun belowApi29DeclaresNoType() {
        listOf(24, 26, 28).forEach { sdk ->
            assertEquals("sdk $sdk should declare no type", noType, ForegroundServiceTypePolicy.typeForSdk(sdk))
        }
    }

    @Test
    fun typedStartForegroundOnlyFromApi29() {
        assertFalse(ForegroundServiceTypePolicy.requiresTypedStartForeground(28))
        assertTrue(ForegroundServiceTypePolicy.requiresTypedStartForeground(29))
        assertTrue(ForegroundServiceTypePolicy.requiresTypedStartForeground(36))
    }

    @Test
    fun everyTypedPlatformGetsANonZeroTypeAndEveryUntypedGetsZero() {
        // No platform may end up asking for a typed startForeground with a 0 type, or an untyped
        // call with a non-zero type — either mismatch is a runtime rejection.
        (24..40).forEach { sdk ->
            val typed = ForegroundServiceTypePolicy.requiresTypedStartForeground(sdk)
            val type = ForegroundServiceTypePolicy.typeForSdk(sdk)
            if (typed) {
                assertTrue("sdk $sdk typed call must have a real type", type != 0)
            } else {
                assertEquals("sdk $sdk untyped call must have no type", 0, type)
            }
        }
    }
}
