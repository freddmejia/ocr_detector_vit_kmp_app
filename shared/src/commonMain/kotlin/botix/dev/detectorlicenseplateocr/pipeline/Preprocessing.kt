package botix.dev.detectorlicenseplateocr.pipeline

/** `x = (v * rescale - mean[c]) / std[c]` per RGB channel, as in Pipeline.md 4.2 and 6.2. */
class Normalization(val rescale: Float, val mean: FloatArray, val std: FloatArray) {
    init {
        require(mean.size == 3 && std.size == 3) { "mean and std need 3 values (RGB)" }
    }
}

/**
 * Resizes to [width]x[height] without keeping the aspect ratio (bilinear), normalizes and writes NCHW float32:
 * flat index `c * height * width + y * width + x`, channels R, G, B.
 */
fun RgbImage.toNchw(
    width: Int,
    height: Int,
    normalization: Normalization,
    out: FloatArray = FloatArray(3 * width * height),
): FloatArray {
    val plane = width * height
    require(out.size == 3 * plane) { "Output buffer holds ${out.size} floats, needs ${3 * plane}" }
    val resized = if (this.width == width && this.height == height) this else resizeBilinear(width, height)
    val (rescale, mean, std) = Triple(normalization.rescale, normalization.mean, normalization.std)
    for (i in 0 until plane) {
        val p = resized.pixels[i]
        out[i] = ((p shr 16 and 0xFF) * rescale - mean[0]) / std[0]
        out[plane + i] = ((p shr 8 and 0xFF) * rescale - mean[1]) / std[1]
        out[2 * plane + i] = ((p and 0xFF) * rescale - mean[2]) / std[2]
    }
    return out
}

/**
 * Resizes like [toNchw] and writes NHWC float32 with the raw 0-255 values (fast-plate-ocr rescales inside the model):
 * flat index `(y * width + x) * 3 + c`, channels R, G, B.
 */
fun RgbImage.toNhwc(width: Int, height: Int, out: FloatArray = FloatArray(3 * width * height)): FloatArray {
    val plane = width * height
    require(out.size == 3 * plane) { "Output buffer holds ${out.size} floats, needs ${3 * plane}" }
    val resized = if (this.width == width && this.height == height) this else resizeBilinear(width, height)
    for (i in 0 until plane) {
        val p = resized.pixels[i]
        out[3 * i] = (p shr 16 and 0xFF).toFloat()
        out[3 * i + 1] = (p shr 8 and 0xFF).toFloat()
        out[3 * i + 2] = (p and 0xFF).toFloat()
    }
    return out
}
