package org.pspp.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pspp.mobile.engine.PsppBridge
import org.pspp.mobile.engine.PsppStatisticalEvaluator

class ExampleUnitTest {
    @Test
    fun verifyApproximationBannerConstant() {
        assertEquals(
            "Approximation mode: results are NOT from the real PSPP engine",
            PsppStatisticalEvaluator.APPROXIMATION_BANNER
        )
        assertEquals(
            PsppStatisticalEvaluator.APPROXIMATION_BANNER,
            PsppBridge.APPROXIMATION_BANNER
        )
        assertTrue(PsppBridge.getEngineVersionBanner().isNotBlank())
    }
}
