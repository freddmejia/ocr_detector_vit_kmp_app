package botix.dev.detectorlicenseplateocr.pipeline

import botix.dev.detectorlicenseplateocr.scanner.ScanRegion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostprocessingTest {
    @Test
    fun detectionMatchesReferenceExample() {
        // Pipeline.md 10.2: 1000x500 image, logit 0, box (0.5, 0.5, 0.2, 0.1).
        val detections = decodeDetections(floatArrayOf(0f), floatArrayOf(0.5f, 0.5f, 0.2f, 0.1f), 1, 1000, 500, threshold = 0.4f)
        assertEquals(1, detections.size)
        assertEquals(0.5f, detections[0].score)
        val box = detections[0].box
        assertEquals(listOf(400f, 225f, 600f, 275f), listOf(box.x0, box.y0, box.x1, box.y1))

        val crop = plateCropRect(box, 1000, 500, PipelineParams())
        assertEquals(PixelRect(384, 217, 616, 283), crop)
        assertEquals(232, crop.width)
    }

    @Test
    fun thresholdAppliesToSigmoidAndResultsAreSorted() {
        // sigmoid(-0.5) = 0.378 is dropped; sigmoid(0.2) = 0.55 and sigmoid(2) = 0.88 are kept, best first.
        val logits = floatArrayOf(-0.5f, 0.2f, 2f)
        val boxes = FloatArray(12) { 0.5f }
        val detections = decodeDetections(logits, boxes, 3, 100, 100, threshold = 0.4f)
        assertEquals(2, detections.size)
        assertTrue(detections[0].score > detections[1].score)
        assertTrue(abs(detections[0].score - 0.8808f) < 1e-4f)
    }

    @Test
    fun cropIsClampedToTheImage() {
        val crop = plateCropRect(Box(-5f, -3f, 105f, 52f), 100, 50, PipelineParams())
        assertEquals(PixelRect(0, 0, 100, 50), crop)
    }

    @Test
    fun narrowCropIsNotReadable() {
        val crop = plateCropRect(Box(10f, 10f, 60f, 30f), 1000, 1000, PipelineParams())
        assertTrue(crop.width < PipelineParams().minOcrWidth)
    }

    @Test
    fun rgbImageCropCopiesTheRightPixels() {
        val image = RgbImage(4, 3, IntArray(12) { it })
        val crop = image.crop(PixelRect(1, 1, 3, 3))
        assertEquals(listOf(5, 6, 9, 10), crop.pixels.toList())
    }

    @Test
    fun scanRegionIsCenteredAndKeepsItsAspectRatio() {
        for ((w, h) in listOf(1080f to 2340f, 2340f to 1080f, 411f to 891f)) {
            val r = ScanRegion.inView(w, h)
            assertTrue(abs((r.left + r.right) / 2 - 0.5f) < 1e-5f, "not centered for ${w}x$h")
            assertTrue(abs(r.width * w / (r.height * h) - ScanRegion.ASPECT_RATIO) < 1e-3f, "aspect for ${w}x$h")
            assertTrue(r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f)
        }
    }
}
