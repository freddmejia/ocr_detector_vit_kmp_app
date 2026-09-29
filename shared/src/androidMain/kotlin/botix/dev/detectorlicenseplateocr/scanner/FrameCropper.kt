package botix.dev.detectorlicenseplateocr.scanner

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import botix.dev.detectorlicenseplateocr.pipeline.RgbImage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cuts the guide rectangle out of a camera frame, upright and at full frame resolution.
 *
 * `ImageProxy.toBitmap()` is unrotated and covers the whole buffer. The frame's `cropRect` is the part the preview
 * shows (the controller shares the PreviewView's viewport with ImageAnalysis). Mapping goes view -> upright visible
 * frame (FILL_CENTER) -> buffer, and the result is rotated exactly once. Front camera frames are not mirrored, but the
 * guide is horizontally centered, so the same region applies.
 */
internal object FrameCropper {
    fun cropRegion(image: ImageProxy, viewWidth: Int, viewHeight: Int, region: NormalizedRect): RgbImage? {
        val crop = image.cropRect
        val rotation = image.imageInfo.rotationDegrees
        val sideways = rotation % 180 != 0
        val uprightWidth = (if (sideways) crop.height() else crop.width()).toFloat()
        val uprightHeight = (if (sideways) crop.width() else crop.height()).toFloat()

        // FILL_CENTER: the frame is scaled to cover the view and centered; the view sees the middle of it.
        val scale = max(viewWidth / uprightWidth, viewHeight / uprightHeight)
        val visibleWidth = viewWidth / scale
        val visibleHeight = viewHeight / scale
        val offsetX = (uprightWidth - visibleWidth) / 2f
        val offsetY = (uprightHeight - visibleHeight) / 2f
        val left = offsetX + region.left * visibleWidth
        val top = offsetY + region.top * visibleHeight
        val right = offsetX + region.right * visibleWidth
        val bottom = offsetY + region.bottom * visibleHeight

        // Upright -> buffer coordinates (relative to cropRect), undoing the clockwise rotation.
        val cw = crop.width().toFloat()
        val ch = crop.height().toFloat()
        fun toBuffer(ux: Float, uy: Float): Pair<Float, Float> = when (rotation) {
            90 -> uy to ch - ux
            180 -> cw - ux to ch - uy
            270 -> cw - uy to ux
            else -> ux to uy
        }
        val (ax, ay) = toBuffer(left, top)
        val (bx, by) = toBuffer(right, bottom)
        val x0 = (minOf(ax, bx).roundToInt() + crop.left).coerceIn(0, image.width)
        val y0 = (minOf(ay, by).roundToInt() + crop.top).coerceIn(0, image.height)
        val x1 = (maxOf(ax, bx).roundToInt() + crop.left).coerceIn(0, image.width)
        val y1 = (maxOf(ay, by).roundToInt() + crop.top).coerceIn(0, image.height)
        if (x1 - x0 < 2 || y1 - y0 < 2) return null

        val frame = image.toBitmap()
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val upright = Bitmap.createBitmap(frame, x0, y0, x1 - x0, y1 - y0, matrix, false)
        return upright.toRgbImage().also {
            if (upright !== frame) upright.recycle()
            frame.recycle()
        }
    }
}

/** ARGB_8888 bitmap to RGB (alpha dropped). */
internal fun Bitmap.toRgbImage(): RgbImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    for (i in pixels.indices) pixels[i] = pixels[i] and 0xFFFFFF
    return RgbImage(width, height, pixels)
}
