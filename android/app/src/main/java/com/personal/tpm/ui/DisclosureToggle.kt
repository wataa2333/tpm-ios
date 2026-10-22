package com.personal.tpm.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Expand control without an oversized pill-shaped press overlay around the text. */
@Composable
fun DisclosureToggle(expanded: Boolean, label: String, compact: Boolean = false, onClick: () -> Unit) {
    val angle by animateFloatAsState(if(expanded) 180f else 0f,tween(Motion.Normal,easing=Motion.Change),label="disclosure arrow")
    Row(Modifier.fillMaxWidth().heightIn(min=if(compact) 40.dp else 48.dp)
        .clickable(interactionSource=remember{MutableInteractionSource()},indication=null,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
        Icon(Icons.Default.ExpandMore,contentDescription=if(expanded) "收起" else "展开",tint=MaterialTheme.colorScheme.primary,modifier=Modifier.graphicsLayer{rotationZ=angle})
    }
}
