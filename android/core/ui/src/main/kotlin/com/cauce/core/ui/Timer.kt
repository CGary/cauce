package com.cauce.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/** Counts down once per second and reports when it reaches zero. */
@Composable
fun Timer(
    seconds: Int,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    var remaining by remember(seconds) { mutableIntStateOf(seconds) }

    LaunchedEffect(seconds) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
        onFinished()
    }

    Text(
        text = formatSeconds(remaining),
        modifier = modifier,
        style = MaterialTheme.typography.displaySmall,
    )
}

internal fun formatSeconds(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / 60
    val secondsPart = safe % 60
    return "%02d:%02d".format(minutes, secondsPart)
}
