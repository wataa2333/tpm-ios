package com.personal.tpm.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.personal.tpm.ui.theme.ThemeConfig

private const val StartupSweepDurationMillis = 1_400
private const val StartupSweepLeadMillis = 200

@Composable
internal fun HomeStartupSkeleton(modifier: Modifier, animate: Boolean) {
    val colors = MaterialTheme.colorScheme
    val phase = if (animate) rememberInfiniteTransition(label = "startup skeleton").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(StartupSweepDurationMillis, easing = LinearEasing),
            initialStartOffset = StartOffset(StartupSweepLeadMillis, StartOffsetType.FastForward),
        ),
        label = "startup skeleton light",
    ) else null
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + ThemeConfig.displayTop.dp
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + ThemeConfig.displayBottom.dp
    val displayStart = ThemeConfig.displayStart.dp
    val displayEnd = ThemeConfig.displayEnd.dp
    StatusBarIcons(dark = colors.background.luminance() > .5f)

    Box(modifier.drawWithCache {
        val margin = 16.dp.toPx()
        val available = (size.width - displayStart.toPx() - displayEnd.toPx()).coerceAtLeast(0f)
        val width = (available.coerceAtMost(ContentMaxWidth.toPx()) - margin * 2).coerceAtLeast(0f)
        val left = displayStart.toPx() + (available - width) / 2
        val top = statusTop.toPx()
        val footerTop = (size.height - navBottom.toPx() - 74.dp.toPx()).coerceAtLeast(0f)
        val gap = 12.dp.toPx()
        val columns = ((width + gap) / (150.dp.toPx() + gap)).toInt().coerceAtLeast(1)
        val tileWidth = (width - gap * (columns - 1)) / columns
        val wideTop = top + 192.dp.toPx()
        val wideHeight = 84.dp.toPx()
        val tileHeight = 132.dp.toPx()
        val rowGap = 16.dp.toPx()
        val cardRadius = CornerRadius(20.dp.toPx())
        val blockRadius = CornerRadius(30.dp.toPx())
        val cards = mutableListOf<Rect>()
        val blocks = mutableListOf<Rect>()
        fun block(x: Float, y: Float, w: Float, h: Float) { blocks.add(Rect(x, y, x + w, y + h)) }

        if (width > 0f) {
            block(left + 4.dp.toPx(), top + 75.dp.toPx(), 84.dp.toPx(), 27.dp.toPx())
            block(left + 4.dp.toPx(), top + 149.dp.toPx(), 112.dp.toPx(), 16.dp.toPx())
            cards.add(Rect(left, wideTop, left + width, wideTop + wideHeight))
            block(left + margin, wideTop + margin, 52.dp.toPx(), 52.dp.toPx())
            block(left + 84.dp.toPx(), wideTop + 25.dp.toPx(), (width * .32f), 15.dp.toPx())
            block(left + 84.dp.toPx(), wideTop + 47.dp.toPx(), (width * .45f), 10.dp.toPx())

            repeat(6) { index ->
                val x = left + (index % columns) * (tileWidth + gap)
                val y = wideTop + wideHeight + rowGap + (index / columns) * (tileHeight + rowGap)
                if (y < footerTop) {
                    cards.add(Rect(x, y, x + tileWidth, y + tileHeight))
                    block(x + margin, y + margin, 52.dp.toPx(), 52.dp.toPx())
                    block(x + tileWidth - margin - 34.dp.toPx(), y + 31.dp.toPx(), 34.dp.toPx(), 21.dp.toPx())
                    block(x + margin, y + tileHeight - 33.dp.toPx(), tileWidth * .44f, 16.dp.toPx())
                }
            }
        }
        val footer = Rect(left, footerTop, left + width, footerTop + 66.dp.toPx())

        onDrawBehind {
            drawRect(colors.background)
            for (card in cards) drawRoundRect(colors.surfaceContainerLowest, card.topLeft, card.size, cardRadius)
            val sweep = (phase?.value ?: 0f) * (size.height + 220.dp.toPx()) - 110.dp.toPx()
            for (block in blocks) {
                val light = (1f - kotlin.math.abs(block.center.y - sweep) / 110.dp.toPx()).coerceIn(0f, 1f)
                drawRoundRect(lerp(colors.surfaceContainerHighest, colors.surfaceContainer, light), block.topLeft, block.size, blockRadius)
            }
            if (width > 0f) {
                drawRect(colors.background, Offset(0f, footerTop), Size(size.width, size.height - footerTop))
                drawRoundRect(colors.surfaceContainerLowest, footer.topLeft, footer.size, blockRadius)
                repeat(5) { index ->
                    val centerX = left + width * (index + .5f) / 5f
                    drawCircle(colors.surfaceContainerHighest, 9.dp.toPx(), Offset(centerX, footerTop + 24.dp.toPx()))
                    drawRoundRect(colors.surfaceContainerHigh, Offset(centerX - 15.dp.toPx(), footerTop + 42.dp.toPx()),
                        Size(30.dp.toPx(), 7.dp.toPx()), blockRadius)
                }
            }
        }
    })
}
