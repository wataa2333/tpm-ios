package com.personal.tpm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassPolicyTest {
    @Test fun disablingGlassBypassesEveryEffectOnEverySupportedVersion() {
        for (sdk in listOf(29, 30, 31, 32, 33, 35, 36)) {
            for (hardware in listOf(false, true)) {
                assertEquals(GlassRendering.Solid, glassRendering(false, sdk, hardware))
            }
        }
    }

    @Test fun olderDevicesNeverEnterAnUnsupportedShaderPath() {
        assertEquals(GlassRendering.Translucent, glassRendering(true, 29, true))
        assertEquals(GlassRendering.Translucent, glassRendering(true, 30, true))
        assertEquals(GlassRendering.Blur, glassRendering(true, 31, true))
        assertEquals(GlassRendering.Blur, glassRendering(true, 32, true))
        assertEquals(GlassRendering.Liquid, glassRendering(true, 33, true))
        assertEquals(GlassRendering.Liquid, glassRendering(true, 36, true))
    }

    @Test fun softwareRenderingAlwaysFallsBackWithoutGpuEffects() {
        for (sdk in 29..36) assertEquals(GlassRendering.Translucent, glassRendering(true, sdk, false))
    }
}
