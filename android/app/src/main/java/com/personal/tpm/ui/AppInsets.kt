package com.personal.tpm.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/** Top bars and bottom actions own their insets; scrolling content can draw behind transparent bars. */
@Composable
fun appContentInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
