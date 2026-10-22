package com.personal.tpm.ui

import kotlin.math.abs

enum class ChartGestureIntent { Undecided, Page, Chart }

fun chartGestureIntent(pointers:Int, horizontal:Float, vertical:Float, slop:Float):ChartGestureIntent = when {
    pointers<=0 -> ChartGestureIntent.Undecided
    pointers>=2 -> ChartGestureIntent.Chart
    abs(vertical)>slop && abs(vertical)>=abs(horizontal) -> ChartGestureIntent.Page
    abs(horizontal)>slop && abs(horizontal)>abs(vertical) -> ChartGestureIntent.Chart
    else -> ChartGestureIntent.Undecided
}
