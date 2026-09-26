package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyframeIntervalPolicyTest {

    @Test
    fun theCommonThreeSecondGopIsRequestedAsThreeSeconds() {
        assertEquals(3.0f, KeyframeIntervalPolicy.iFrameIntervalSeconds(3_003_000L), 0f)
        assertEquals(2.0f, KeyframeIntervalPolicy.iFrameIntervalSeconds(2_002_000L), 0f)
    }

    @Test
    fun theIntervalIsNeverFinerThanMediaThreesDefaultNorCoarserThanTheCap() {
        assertEquals(KeyframeIntervalPolicy.MIN_SECONDS, KeyframeIntervalPolicy.iFrameIntervalSeconds(500_000L), 0f)
        assertEquals(KeyframeIntervalPolicy.MAX_SECONDS, KeyframeIntervalPolicy.iFrameIntervalSeconds(10_000_000L), 0f)
    }

    @Test
    fun anUnindexedSourceGetsTheTwoSecondDefaultNotMediaThreesOneSecond() {
        assertEquals(KeyframeIntervalPolicy.WHEN_UNKNOWN_SECONDS, KeyframeIntervalPolicy.iFrameIntervalSeconds(null), 0f)
        assertEquals(KeyframeIntervalPolicy.WHEN_UNKNOWN_SECONDS, KeyframeIntervalPolicy.iFrameIntervalSeconds(0L), 0f)
    }

    @Test
    fun theTypicalGapIsTheMedianSoSceneCutsDoNotShortenIt() {
        val gaps = listOf(3_000_000L, 3_000_000L, 1_200_000L, 3_000_000L, 800_000L, 3_000_000L, 3_000_000L)
        assertEquals(3_000_000L, KeyframeIntervalPolicy.typicalGapUs(gaps))
        assertNull(KeyframeIntervalPolicy.typicalGapUs(emptyList()))
        assertNull(KeyframeIntervalPolicy.typicalGapUs(listOf(0L, -5L)))
    }

    @Test
    fun describeIsOneDecimal() {
        assertEquals("3.0s", KeyframeIntervalPolicy.describe(3.0f))
    }
}
