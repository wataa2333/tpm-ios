package com.personal.tpm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** Animate state labels only when their value changes; initial composition stays still. */
@Composable
fun AnimatedLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    minLines: Int = 1,
    onTextLayout: ((androidx.compose.ui.text.TextLayoutResult) -> Unit)? = null,
) {
    AnimatedContent(targetState = text, modifier = modifier, label = "stateLabel", transitionSpec = {
        (fadeIn(tween(Motion.Fast)) + slideInVertically(tween(Motion.Normal, easing = Motion.Change)) { it / 5 })
            .togetherWith(fadeOut(tween(Motion.Instant)) + slideOutVertically(tween(Motion.Normal, easing = Motion.Change)) { -it / 5 })
            .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(Motion.Normal, easing = Motion.Change) }))
    }) { value ->
        Text(value, color = color, style = style, maxLines = maxLines, softWrap = softWrap,
            fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
            letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
            lineHeight = lineHeight, overflow = overflow, minLines = minLines, onTextLayout = onTextLayout)
    }
}

/** Keep coloured spans intact in changing chart legends and status summaries. */
@Composable
fun AnimatedLabel(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    AnimatedContent(targetState = text, modifier = modifier, label = "annotatedStateLabel", transitionSpec = {
        fadeIn(tween(Motion.Fast)).togetherWith(fadeOut(tween(Motion.Instant)))
            .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(Motion.Normal, easing = Motion.Change) }))
    }) { value ->
        Text(value, color = color, style = style, maxLines = maxLines, softWrap = softWrap, overflow = overflow)
    }
}
