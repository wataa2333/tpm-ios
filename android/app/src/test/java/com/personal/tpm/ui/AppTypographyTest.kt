package com.personal.tpm.ui

import androidx.compose.material3.Typography
import com.personal.tpm.ui.theme.ThemeConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class AppTypographyTest {
    @Test fun everyTextRoleUsesBundledFontsIncludingCountsAndCompactForms() {
        for (type in listOf(ThemeConfig.typography, ThemeConfig.compactTypography)) {
            for (style in roles(type)) assertEquals(ThemeConfig.fontFamily, style.fontFamily)
        }
    }

    private fun roles(t: Typography) = listOf(
        t.displayLarge, t.displayMedium, t.displaySmall,
        t.headlineLarge, t.headlineMedium, t.headlineSmall,
        t.titleLarge, t.titleMedium, t.titleSmall,
        t.bodyLarge, t.bodyMedium, t.bodySmall,
        t.labelLarge, t.labelMedium, t.labelSmall,
    )
}
