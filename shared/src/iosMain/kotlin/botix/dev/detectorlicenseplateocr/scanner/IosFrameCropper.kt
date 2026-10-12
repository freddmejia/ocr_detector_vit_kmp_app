@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.detectorlicenseplateocr.scanner

import botix.dev.detectorlicenseplateocr.pipeline.PixelRect
import botix.dev.detectorlicenseplateocr.pipeline.RgbImage
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cuts the guide rectangle out of a camera frame, upright and at full frame resolution: the iOS counterpart of
 * Android's `FrameCropper`.
 *
 * `AVCaptureVideoDataOutput` buffers are left in the sensor's orientation (the data connection's rotation is not
 * set), and [rotation] is the clockwise angle that shows them the way the preview does. Mapping goes view -> upright
 * frame (aspect fill, like the preview layer) -> buffer, and the pixels are rotated exactly once, while copying.
 * Front camera frames are not mirrored, but the guide is horizontally centered, so the same region applies.
 */
internal object IosFrameCropper {
    /** The guide in upright-frame pixels, or null if it is degenerate. */
    fun uprightRegion(
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int,
        viewWidth: Int,
        viewHeight: Int,
        region: NormalizedRect,
    ): PixelRect? {
        val sideways = rotation % 180 != 0
        val uprightWidth = if (sideways) bufferHeight else bufferWidth
        val uprightHeight = if (sideways) bufferWidth else bufferHeight

        // Aspect fill: the frame is scaled to cover the view and centered; the view sees the middle of it.
        val scale = max(viewWidth / uprightWidth.toFloat(), viewHeight / uprightHeight.toFloat())
        val visibleWidth = viewWidth / scale
        val visibleHeight = viewHeight / scale
        val offsetX = (uprightWidth - visibleWidth) / 2f
        val offsetY = (uprightHeight - visibleHeight) / 2f
        val rect = PixelRect(
            left = (offsetX + region.left * visibleWidth).roundToInt().coerceIn(0, uprightWidth),
            top = (offsetY + region.top * visibleHeight).roundToInt().coerceIn(0, uprightHeight),
            right = (offsetX + region.right * visibleWidth).roundToInt().coerceIn(0, uprightWidth),
            bottom = (offsetY + region.bottom * visibleHeight).roundToInt().coerceIn(0, uprightHeight),
        )
        return rect.takeIf { it.width >= 2 && it.height >= 2 }
    }

    /**
     * Copies [rect] (upright coordinates) out of a buffer of [bufferWidth]x[bufferHeight] that needs a clockwise
     * [rotation] to be upright. [pixelAt] returns the 0xRRGGBB pixel at buffer coordinates.
     */
    inline fun cropUpright(
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int,
        rect: PixelRect,
        pixelAt: (x: Int, y: Int) -> Int,
    ): RgbImage {
        val pixels = IntArray(rect.width * rect.height)
        var i = 0
        for (uy in rect.top until rect.bottom) {
            for (ux in rect.left until rect.right) {
                pixels[i++] = when (rotation) {
                    90 -> pixelAt(uy, bufferHeight - 1 - ux)
                    180 -> pixelAt(bufferWidth - 1 - ux, bufferHeight - 1 - uy)
                    270 -> pixelAt(bufferWidth - 1 - uy, ux)
                    else -> pixelAt(ux, uy)
                }
            }
        }
        return RgbImage(rect.width, rect.height, pixels)
    }

    /** Guide crop from a locked `32BGRA` pixel buffer (bytes B, G, R, A), converted to RGB. */
    fun cropBgra(
        base: CPointer<UByteVar>,
        bytesPerRow: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotation: Int,
        viewWidth: Int,
        viewHeight: Int,
        region: NormalizedRect,
    ): RgbImage? {
        val rect = uprightRegion(bufferWidth, bufferHeight, rotation, viewWidth, viewHeight, region) ?: return null
        return cropUpright(bufferWidth, bufferHeight, rotation, rect) { x, y ->
            val o = y * bytesPerRow + x * 4
            (base[o + 2].toInt() shl 16) or (base[o + 1].toInt() shl 8) or base[o].toInt()
        }
    }
}

/** Nearest right angle, 0-270, of a rotation in degrees such as AVFoundation's `videoRotationAngle`. */
internal fun rightAngle(degrees: Double): Int = ((degrees / 90.0).roundToInt() * 90).mod(360)
