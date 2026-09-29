package botix.dev.detectorlicenseplateocr.scanner

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Dimmed surroundings, the guide rectangle with corner brackets, a sweep line while scanning and the found plate. */
@Composable
internal fun ScanOverlay(state: ScannerUiState, modifier: Modifier = Modifier) {
    val reading = state.lastReading
    val frameColor by animateColorAsState(
        when {
            !state.isScanning -> Color.White
            reading?.outcome == ReadingOutcome.READ -> ScannerColors.Accent
            reading?.outcome == ReadingOutcome.TOO_SMALL || reading?.outcome == ReadingOutcome.NO_TEXT ->
                ScannerColors.Warning
            else -> Color.White
        },
        label = "frameColor",
    )
    val sweep = rememberInfiniteTransition(label = "sweep")
    val sweepProgress by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweepProgress",
    )
    // Fades the plate box in on every new reading.
    val boxAlpha = remember { Animatable(0f) }
    LaunchedEffect(reading?.sequence, state.isScanning) {
        if (!reading?.plateBoxes.isNullOrEmpty() && state.isScanning) {
            boxAlpha.snapTo(0f)
            boxAlpha.animateTo(1f, tween(250))
        } else {
            boxAlpha.snapTo(0f)
        }
    }

    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val region = ScanRegion.inView(size.width, size.height)
        val rect = Rect(
            region.left * size.width,
            region.top * size.height,
            region.right * size.width,
            region.bottom * size.height,
        )
        val corner = 20.dp.toPx()

        drawRect(ScannerColors.Scrim)
        drawRoundRect(Color.Transparent, rect.topLeft, rect.size, CornerRadius(corner), blendMode = BlendMode.Clear)
        drawRoundRect(frameColor.copy(alpha = 0.55f), rect.topLeft, rect.size, CornerRadius(corner), style = Stroke(1.5.dp.toPx()))
        drawCornerBrackets(rect, corner, frameColor)

        if (state.isScanning) {
            val y = rect.top + corner / 2 + (rect.height - corner) * sweepProgress
            val glow = 18.dp.toPx()
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Transparent, ScannerColors.Accent.copy(alpha = 0.35f), Color.Transparent),
                    startY = y - glow,
                    endY = y + glow,
                ),
                topLeft = Offset(rect.left + corner / 2, y - glow),
                size = Size(rect.width - corner, glow * 2),
            )
            drawLine(
                ScannerColors.Accent,
                Offset(rect.left + corner / 2, y),
                Offset(rect.right - corner / 2, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        if (boxAlpha.value > 0f) {
            val color = ScannerColors.PlateBox
            reading?.plateBoxes?.forEach { plate ->
                val plateRect = Rect(
                    rect.left + plate.left * rect.width,
                    rect.top + plate.top * rect.height,
                    rect.left + plate.right * rect.width,
                    rect.top + plate.bottom * rect.height,
                )
                drawRoundRect(color.copy(alpha = 0.18f * boxAlpha.value), plateRect.topLeft, plateRect.size, CornerRadius(6.dp.toPx()))
                drawRoundRect(color.copy(alpha = boxAlpha.value), plateRect.topLeft, plateRect.size, CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.drawCornerBrackets(rect: Rect, corner: Float, color: Color) {
    val length = 34.dp.toPx()
    val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
    val d = corner * 2
    fun bracket(arc: Rect, startAngle: Float, from: Offset, to: Offset) {
        val path = Path().apply {
            moveTo(from.x, from.y)
            arcTo(arc, startAngle, 90f, forceMoveTo = false)
            lineTo(to.x, to.y)
        }
        drawPath(path, color, style = stroke)
    }
    with(rect) {
        bracket(Rect(left, top, left + d, top + d), 180f, Offset(left, top + length), Offset(left + length, top))
        bracket(Rect(right - d, top, right, top + d), 270f, Offset(right - length, top), Offset(right, top + length))
        bracket(Rect(right - d, bottom - d, right, bottom), 0f, Offset(right, bottom - length), Offset(right - length, bottom))
        bracket(Rect(left, bottom - d, left + d, bottom), 90f, Offset(left + length, bottom), Offset(left, bottom - length))
    }
}
