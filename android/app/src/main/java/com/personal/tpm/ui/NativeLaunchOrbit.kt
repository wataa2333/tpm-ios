package com.personal.tpm.ui

import android.content.res.ColorStateList
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.SystemClock
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.personal.tpm.R

/** Platform animated vector: the loading rotation need not update Compose every frame. */
@Composable
internal fun NativeLaunchOrbit(modifier: Modifier, color: Color, onStarted: (Long) -> Unit) {
    AndroidView(modifier = modifier, factory = { context ->
        ImageView(context).apply {
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(R.drawable.launch_orbit_animated)
            imageTintList = ColorStateList.valueOf(color.toArgb())
            onStarted(SystemClock.uptimeMillis())
            (drawable as AnimatedVectorDrawable).start()
        }
    }, update = { it.imageTintList = ColorStateList.valueOf(color.toArgb()) },
        onRelease = { (it.drawable as? AnimatedVectorDrawable)?.stop() })
}
