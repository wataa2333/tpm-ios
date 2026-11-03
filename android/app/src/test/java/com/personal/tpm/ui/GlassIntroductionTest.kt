package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class GlassIntroductionTest {
    @Test fun noticeDescribesActualCapabilitiesAndKeepsManualOffState() {
        assertTrue(glassSupportDescription(GlassRendering.Liquid, true).contains("模糊与镜头折射"))
        assertTrue(glassSupportDescription(GlassRendering.Blur, true).contains("折射需要 Android 13"))
        assertTrue(glassSupportDescription(GlassRendering.Translucent, true).contains("半透明兼容"))
        assertTrue(glassSupportDescription(GlassRendering.Liquid, false).contains("已关闭"))
    }
}
