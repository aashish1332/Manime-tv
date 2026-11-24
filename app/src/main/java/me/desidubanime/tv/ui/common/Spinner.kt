package me.desidubanime.tv.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * Indeterminate spinner that ALWAYS turns.
 *
 * Every spinner in the app used to be Material3's `CircularProgressIndicator`.
 * That one animates through a `rememberInfiniteTransition`, which Compose scales
 * by the platform `MotionDurationScale`. Many Android TV boxes ship with
 * "Animator duration scale" at 0 (and developers turn animations off), and the
 * scale factor of 0 stops infinite transitions dead — so the circle rendered as
 * a frozen arc that never swept. The user's report was literal: "loading circle
 * doesn't animate circling".
 *
 * This one takes its angle from the frame clock's own timestamps, which the
 * duration scale does not touch, and it suspends on `withFrameNanos` — so it
 * costs nothing while the app is in the background and cannot drift when the
 * device is busy (the angle is derived from elapsed time, not from a counter).
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier.size(44.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val zero = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsed = (now - zero) / 1_000_000L
                angle = (elapsed % ROTATION_MS).toFloat() / ROTATION_MS * 360f
            }
        }
    }
    Canvas(modifier) {
        val stroke = Stroke(width = strokeWidth.toPx().coerceAtMost(size.minDimension / 2f), cap = StrokeCap.Round)
        val extent = size.minDimension - stroke.width
        if (extent <= 0f) return@Canvas
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset((size.width - extent) / 2f, (size.height - extent) / 2f),
            size = Size(extent, extent),
            style = stroke,
        )
    }
}

/** One full turn. Slow enough to read as a spinner, not a strobe. */
private const val ROTATION_MS = 900L
