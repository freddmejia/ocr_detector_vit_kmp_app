package botix.dev.detectorlicenseplateocr.pipeline

/** 8-bit RGB image, row-major, one pixel per Int packed as 0xRRGGBB. Alpha bits, if any, are ignored. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "Image must not be empty: ${width}x$height" }
        require(pixels.size == width * height) { "Expected ${width * height} pixels, got ${pixels.size}" }
    }

    fun crop(rect: PixelRect): RgbImage {
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height) {
            "Crop $rect is outside a ${width}x$height image"
        }
        require(rect.width > 0 && rect.height > 0) { "Crop $rect is empty" }
        val out = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) {
            pixels.copyInto(out, y * rect.width, (rect.top + y) * width + rect.left, (rect.top + y) * width + rect.right)
        }
        return RgbImage(rect.width, rect.height, out)
    }
}

/** Integer pixel rectangle, right and bottom exclusive. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Box corners in pixels of the image it was detected on (float, may slightly exceed the image). */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}
