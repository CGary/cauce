package com.cauce.feature.alarmas.device

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** SPEC 2.3/§8: how long Hecho and Posponer must be held on the alarm screen. */
internal const val HOLD_MILLIS = 1_000

/**
 * A button that only acts after being held for [HOLD_MILLIS], so touches in a pocket cannot
 * attend the alarm (SPEC §8). While held, the button fills from left to right; releasing early
 * empties it and calls [onShortPress]. TalkBack users get a plain click action instead of the
 * hold, through the node's semantics.
 */
@Composable
internal fun HoldButton(
    text: String,
    onConfirmed: () -> Unit,
    onShortPress: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    fill: Color = MaterialTheme.colorScheme.inversePrimary,
) {
    val confirm by rememberUpdatedState(onConfirmed)
    val shortPress by rememberUpdatedState(onShortPress)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(ButtonDefaults.shape)
            .background(container)
            .drawBehind {
                drawRect(fill, size = Size(size.width * progress.value, size.height))
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$text. Mantén presionado para confirmar"
                onClick(label = text) {
                    confirm()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        var done = false
                        val hold: Job = scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                            done = true
                            confirm()
                        }
                        tryAwaitRelease()
                        if (!done) {
                            hold.cancel()
                            scope.launch { progress.snapTo(0f) }
                            shortPress()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}
