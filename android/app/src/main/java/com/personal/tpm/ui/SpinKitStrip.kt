package com.personal.tpm.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.personal.tpm.ui.theme.ThemeConfig.LoadingStyle
import kotlin.math.*

// Native Compose adaptation of SpinKit's Plane, Bounce, Wave, Circle, Grid and Chase concepts/keyframe phases.
// Copyright (c) 2020 Tobias Ahlin. MIT license included in assets/licenses/SpinKit.txt.
val LoadingStyle.iconStyle: Boolean get() = this in listOf(LoadingStyle.Ring, LoadingStyle.Chase, LoadingStyle.Grid, LoadingStyle.Plane, LoadingStyle.Bounce, LoadingStyle.Equalizer)

@Composable
fun SpinKitStrip(style: LoadingStyle, running: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = if (running) {
        val transition = rememberInfiniteTransition(label = "SpinKit")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase")
        value
    } else pull.coerceIn(0f, 1f)
    Box(modifier.height(24.dp), Alignment.Center) {
        Canvas(Modifier.width(54.dp).height(22.dp)) {
            val c = center
            val r = size.height * .4f
            when (style) {
                LoadingStyle.Ring -> drawArc(color, phase * 360, 80f + 200f * abs(sin(phase * PI)).toFloat(), false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2.dp.toPx()))
                LoadingStyle.Chase -> repeat(6) { i ->
                    val angle = phase * 2 * PI + i * PI / 3
                    val pulse = .35f + .65f * ((sin(phase * 2 * PI - i * .6) + 1) / 2).toFloat()
                    drawCircle(color, r * .22f * pulse, Offset(c.x + cos(angle).toFloat() * r, c.y + sin(angle).toFloat() * r))
                }
                LoadingStyle.Grid -> repeat(9) { i ->
                    val x = i % 3; val y = i / 3
                    val scale = (.2 + .8 * max(0.0, sin(phase * 2 * PI - (x + y) * .4))).toFloat()
                    val side = r * .58f * scale
                    drawRect(color, Offset(c.x + (x - 1) * r * .7f - side / 2, c.y + (y - 1) * r * .7f - side / 2), Size(side, side))
                }
                LoadingStyle.Plane -> rotate(phase * 180, c) {
                    val width = r * 1.8f * abs(cos(phase * 2 * PI)).toFloat().coerceAtLeast(.05f)
                    drawRect(color, Offset(c.x - width / 2, c.y - r * .8f), Size(width, r * 1.6f))
                }
                LoadingStyle.Bounce -> repeat(2) { i ->
                    val scale = ((sin((phase + i * .5) * 2 * PI) + 1) / 2).toFloat()
                    drawCircle(color.copy(alpha = .6f), r * scale, c)
                }
                LoadingStyle.Equalizer -> repeat(5) { i ->
                    val h = size.height * (.3f + .7f * max(0f, sin(phase * 2 * PI - i * .5).toFloat()))
                    drawRect(color, Offset(c.x - 15.dp.toPx() + i * 6.dp.toPx(), c.y - h / 2), Size(4.dp.toPx(), h))
                }
                else -> Unit
            }
        }
    }
}
