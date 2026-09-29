package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pipeline.md 10.1, plus byte-exact parity with Pillow's BILINEAR resize used by the reference notebook. */
class PreprocessingTest {
    private val detector = Normalization(1f / 255f, floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))
    private val ocr = Normalization(0.00392156862745098f, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))

    private fun pixel(r: Int, g: Int, b: Int) = RgbImage(1, 1, intArrayOf((r shl 16) or (g shl 8) or b))

    private fun assertClose(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue(abs(expected[i] - actual[i]) < 1e-5f, "index $i: expected ${expected[i]}, got ${actual[i]}")
        }
    }

    @Test
    fun detectorNormalizationMatchesReference() {
        assertClose(floatArrayOf(2.248908f, -2.035714f, -1.804444f), pixel(255, 0, 0).toNchw(1, 1, detector))
        assertClose(floatArrayOf(-2.117904f, -2.035714f, -1.804444f), pixel(0, 0, 0).toNchw(1, 1, detector))
        assertClose(floatArrayOf(0.074065f, 0.205182f, 0.426492f), pixel(128, 128, 128).toNchw(1, 1, detector))
    }

    @Test
    fun ocrNormalizationMatchesReference() {
        assertClose(floatArrayOf(1f, -1f, -1f), pixel(255, 0, 0).toNchw(1, 1, ocr))
        assertClose(floatArrayOf(-1f, -1f, -1f), pixel(0, 0, 0).toNchw(1, 1, ocr))
        assertClose(floatArrayOf(0.003922f, 0.003922f, 0.003922f), pixel(128, 128, 128).toNchw(1, 1, ocr))
    }

    @Test
    fun layoutIsNchw() {
        val redThenBlue = RgbImage(2, 1, intArrayOf(0xFF0000, 0x0000FF))
        assertClose(floatArrayOf(1f, -1f, -1f, -1f, -1f, 1f), redThenBlue.toNchw(2, 1, ocr))
    }

    @Test
    fun uniformImageStaysUniformWhenResized() {
        val gray = RgbImage(37, 23, IntArray(37 * 23) { 0x808080 })
        val out = gray.toNchw(8, 5, ocr)
        assertTrue(out.all { abs(it - 0.003922f) < 1e-5f })
    }

    // Expected values generated with Pillow 10.4: Image.fromarray(testImage()).resize((w, h), Image.BILINEAR).

    @Test
    fun resizeMatchesPillowByteForByte() {
        val expected = intArrayOf(
            67, 147, 131, 157, 113, 118, 120, 99, 161, 103, 142, 118, 123, 168, 83, 139, 126, 149, 124, 110, 150, 115,
            144, 127, 173, 100, 125, 168, 85, 155, 112, 155, 136, 142, 121, 106, 149, 106, 141, 106, 131, 151, 114,
            174, 86, 127, 117, 129, 101, 121, 140, 130, 119, 121, 149, 79, 168, 50, 140, 147, 108, 169, 93, 166, 102,
            129, 121, 104, 191, 111, 152, 94, 163, 119, 110, 191, 71, 165, 101, 136, 145, 130, 168, 90, 181, 66, 140,
            96, 120, 155, 89, 164, 85, 151, 155, 97, 113, 110, 183, 66, 164, 152, 172, 111, 126, 141, 110, 183, 82,
            173, 68, 152, 140, 101, 172, 74, 166, 61, 125, 177, 123, 138, 94, 186, 90, 146, 136, 106, 179, 96, 180,
            105, 162, 109, 117, 169, 76, 163, 87, 156, 111, 127, 166, 80, 166, 83, 125, 121, 133, 112, 100, 191, 73,
            141, 132, 154, 92, 154, 148, 130, 161, 90, 164, 106, 130, 115, 126, 149, 110, 169, 87, 133, 145, 104,
            116, 93, 169, 80, 165, 104, 130, 153, 93, 189, 81, 156, 100, 126, 136, 107, 137, 82, 165, 72, 131, 120,
            101, 186, 108, 167, 100, 142, 146, 88, 100, 114, 155, 98, 152, 96, 162, 65, 110, 143, 107, 159, 80, 159,
            151, 126, 183, 82, 158, 96, 151, 102, 135, 132, 95, 187, 60, 164, 138, 115, 155, 113, 146, 78, 150, 101,
            148, 105, 106, 180, 71, 167, 85, 120, 176, 126, 178, 86, 177, 128, 136, 120, 137, 144, 133, 157, 106,
            169, 66, 152, 119, 119, 159, 89, 170, 100, 150, 130, 109, 108, 98, 174, 83, 163, 123, 133, 158, 85, 172,
            109, 143, 92, 137, 158, 108, 183, 79, 169, 93, 127, 160, 95, 165, 92, 155, 137, 151, 125, 113, 175, 80,
            153, 114, 125, 131, 169, 76, 140, 114, 111, 178, 96, 169, 145, 151, 131, 106, 128, 88, 172, 77, 144, 111,
            133, 153, 85, 195, 73, 145, 137, 110, 161, 106, 146, 126, 166, 102, 115, 139, 104, 133, 70, 164, 113,
            128, 199, 88, 145, 112, 144, 111, 131, 129, 157, 95, 125, 187, 80, 176, 69, 142, 176, 123, 139, 116, 170,
            66, 149, 49, 113, 206, 91, 157, 100, 169, 91, 115, 194, 84, 176, 93, 162, 136, 141, 158, 96, 176, 64,
            144, 84, 131, 154, 109, 195, 71, 158, 99, 117, 124, 105, 161, 93, 170, 55, 156, 106, 115, 153, 110, 165,
            96, 173, 108, 138, 128, 101, 165, 76, 175, 74, 148, 164, 115, 156, 103, 164, 69, 140, 88, 117, 180, 80,
            164, 126, 156, 127, 116, 142, 92, 177, 86, 134, 147, 136, 149, 91, 186, 62, 145, 112, 126, 138, 101, 167,
            63, 133, 118, 143, 101, 158, 121, 144, 131, 97, 154, 98, 158, 96, 128, 192, 105, 195, 104, 175, 113, 133,
            119, 115, 149, 72, 167, 83, 135, 141, 106, 148, 94, 168, 85, 139, 153, 130, 130, 95, 186, 63, 136, 179,
            98, 166, 116, 167, 78, 168, 76, 114, 111, 122, 140, 105, 184, 98, 162, 140, 108, 103, 101, 165, 78, 162,
            123, 126, 204, 83, 171, 111, 164, 77, 136, 170, 95, 185, 55, 164, 108, 125, 117, 107, 189, 68, 148, 136,
            156, 92, 110, 202, 70, 167, 84, 129, 190, 127, 187, 94, 189, 70, 135,
        )
        assertContentEquals(expected, testImage().resizeBilinear(17, 11).channels())
    }

    @Test
    fun resizeChecksumsMatchPillow() {
        val image = testImage()
        mapOf(
            (64 to 45) to 54751593L, // upscale both ways
            (20 to 29) to 10882070L, // horizontal only
            (41 to 13) to 9877321L, // vertical only
            (576 to 576) to 6258035767L, // detector size
            (3 to 2) to 21951L, // heavy downscale
        ).forEach { (size, checksum) ->
            assertEquals(checksum, image.resizeBilinear(size.first, size.second).channels().checksum(), "size $size")
        }
    }

    private fun testImage(): RgbImage {
        val w = 41
        val h = 29
        fun value(x: Int, y: Int, c: Int) = (x * 31 + y * 17 + c * 101 + ((x * y) % 7) * 13 + (x * x * y) % 11 * 5) % 256
        return RgbImage(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (value(x, y, 0) shl 16) or (value(x, y, 1) shl 8) or value(x, y, 2)
        })
    }

    private fun RgbImage.channels(): IntArray = IntArray(pixels.size * 3) { i ->
        val p = pixels[i / 3]
        when (i % 3) {
            0 -> p shr 16 and 0xFF
            1 -> p shr 8 and 0xFF
            else -> p and 0xFF
        }
    }

    private fun IntArray.checksum(): Long {
        var sum = 0L
        for (i in indices) sum += this[i].toLong() * (i % 97 + 1)
        return sum
    }
}
