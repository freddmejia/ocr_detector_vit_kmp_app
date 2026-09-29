package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.math.ceil
import kotlin.math.max

/**
 * Port of Pillow's `Image.resize(size, Image.BILINEAR)` for 8-bit RGB (Resample.c), which is what the reference
 * notebook uses. When shrinking, the triangle filter widens with the scale, so it averages (antialias) instead of
 * sampling 4 pixels; intermediate results are rounded to 8 bits exactly as Pillow does, so outputs match byte for byte.
 */
fun RgbImage.resizeBilinear(outWidth: Int, outHeight: Int): RgbImage {
    require(outWidth > 0 && outHeight > 0) { "Target size must not be empty: ${outWidth}x$outHeight" }
    if (outWidth == width && outHeight == height) return RgbImage(width, height, pixels.copyOf())

    val horizontal = PillowBilinear.coefficients(width, outWidth)
    val vertical = PillowBilinear.coefficients(height, outHeight)
    var source = this
    if (outWidth != width) {
        // Only the rows the vertical pass will read are resampled horizontally.
        val firstRow = vertical.bounds[0]
        val lastRow = vertical.bounds[outHeight * 2 - 2] + vertical.bounds[outHeight * 2 - 1]
        for (i in 0 until outHeight) vertical.bounds[i * 2] -= firstRow
        source = PillowBilinear.horizontalPass(source, firstRow, lastRow - firstRow, outWidth, horizontal)
    }
    if (outHeight != height) {
        source = PillowBilinear.verticalPass(source, outHeight, vertical)
    }
    return source
}

private object PillowBilinear {
    private const val PRECISION_BITS = 32 - 8 - 2

    class Coefficients(val kernelSize: Int, val bounds: IntArray, val weights: IntArray)

    private fun triangle(x: Double): Double {
        val v = if (x < 0.0) -x else x
        return if (v < 1.0) 1.0 - v else 0.0
    }

    fun coefficients(inSize: Int, outSize: Int): Coefficients {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 1.0 * filterScale
        val kernelSize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val weights = IntArray(outSize * kernelSize)
        val raw = DoubleArray(kernelSize)
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val invScale = 1.0 / filterScale
            // C casts truncate toward zero, as does toInt().
            val xmin = max((center - support + 0.5).toInt(), 0)
            val xmax = minOf((center + support + 0.5).toInt(), inSize) - xmin
            var sum = 0.0
            for (x in 0 until xmax) {
                val w = triangle((x + xmin - center + 0.5) * invScale)
                raw[x] = w
                sum += w
            }
            for (x in 0 until xmax) {
                val w = if (sum != 0.0) raw[x] / sum else raw[x]
                weights[xx * kernelSize + x] =
                    if (w < 0) (-0.5 + w * (1 shl PRECISION_BITS)).toInt() else (0.5 + w * (1 shl PRECISION_BITS)).toInt()
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coefficients(kernelSize, bounds, weights)
    }

    private fun clip8(value: Int): Int = (value shr PRECISION_BITS).coerceIn(0, 255)

    fun horizontalPass(src: RgbImage, firstRow: Int, rows: Int, outWidth: Int, c: Coefficients): RgbImage {
        val out = IntArray(outWidth * rows)
        val half = 1 shl (PRECISION_BITS - 1)
        for (y in 0 until rows) {
            val rowStart = (y + firstRow) * src.width
            for (xx in 0 until outWidth) {
                val xmin = c.bounds[xx * 2]
                val xmax = c.bounds[xx * 2 + 1]
                val k = xx * c.kernelSize
                var r = half
                var g = half
                var b = half
                for (x in 0 until xmax) {
                    val p = src.pixels[rowStart + xmin + x]
                    val w = c.weights[k + x]
                    r += (p shr 16 and 0xFF) * w
                    g += (p shr 8 and 0xFF) * w
                    b += (p and 0xFF) * w
                }
                out[y * outWidth + xx] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return RgbImage(outWidth, rows, out)
    }

    fun verticalPass(src: RgbImage, outHeight: Int, c: Coefficients): RgbImage {
        val out = IntArray(src.width * outHeight)
        val half = 1 shl (PRECISION_BITS - 1)
        for (yy in 0 until outHeight) {
            val ymin = c.bounds[yy * 2]
            val ymax = c.bounds[yy * 2 + 1]
            val k = yy * c.kernelSize
            for (x in 0 until src.width) {
                var r = half
                var g = half
                var b = half
                for (y in 0 until ymax) {
                    val p = src.pixels[(ymin + y) * src.width + x]
                    val w = c.weights[k + y]
                    r += (p shr 16 and 0xFF) * w
                    g += (p shr 8 and 0xFF) * w
                    b += (p and 0xFF) * w
                }
                out[yy * src.width + x] = (clip8(r) shl 16) or (clip8(g) shl 8) or clip8(b)
            }
        }
        return RgbImage(src.width, outHeight, out)
    }
}
