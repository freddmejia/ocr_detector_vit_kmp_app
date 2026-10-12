package botix.dev.detectorlicenseplateocr.scanner

import botix.dev.detectorlicenseplateocr.pipeline.PixelRect
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins the rotation direction and the aspect-fill mapping of the iOS guide crop. */
class IosFrameCropperTest {
    // 3x2 buffer, pixel value = its buffer index:
    //   0 1 2
    //   3 4 5
    private val width = 3
    private val height = 2
    private fun crop(rotation: Int): List<Int> {
        val uprightWidth = if (rotation % 180 == 0) width else height
        val uprightHeight = if (rotation % 180 == 0) height else width
        return IosFrameCropper.cropUpright(width, height, rotation, PixelRect(0, 0, uprightWidth, uprightHeight)) { x, y ->
            y * width + x
        }.pixels.toList()
    }

    @Test
    fun rotatesClockwise() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), crop(0))
        // 90 degrees clockwise: the left column, bottom to top, becomes the top row.
        assertEquals(listOf(3, 0, 4, 1, 5, 2), crop(90))
        assertEquals(listOf(5, 4, 3, 2, 1, 0), crop(180))
        assertEquals(listOf(2, 5, 1, 4, 0, 3), crop(270))
    }

    @Test
    fun cropsASubRectangleOfTheUprightFrame() {
        // Upright (90): 3 0 / 4 1 / 5 2. Its bottom-right pixel is buffer index 2.
        val image = IosFrameCropper.cropUpright(width, height, 90, PixelRect(1, 2, 2, 3)) { x, y -> y * width + x }
        assertContentEquals(intArrayOf(2), image.pixels)
    }

    @Test
    fun mapsTheGuideThroughAspectFill() {
        // 1920x1080 landscape buffer rotated to a 1080x1920 portrait frame, shown in a 1000x2000 view: the frame is
        // scaled by 2000/1920 and its sides are cut, so the view sees 960x1920 of it, starting at x = 60.
        val region = NormalizedRect(0.1f, 0.25f, 0.9f, 0.5f)
        val rect = IosFrameCropper.uprightRegion(1920, 1080, 90, 1000, 2000, region)
        assertEquals(PixelRect(60 + 96, 480, 60 + 864, 960), rect)
    }

    @Test
    fun rejectsADegenerateGuide() {
        assertNull(IosFrameCropper.uprightRegion(1920, 1080, 90, 1000, 2000, NormalizedRect(0.5f, 0.5f, 0.5f, 0.6f)))
    }

    @Test
    fun roundsRotationAnglesToRightAngles() {
        assertEquals(listOf(0, 90, 180, 270, 0, 270), listOf(0.0, 90.0, 180.0, 270.0, 360.0, -90.0).map(::rightAngle))
    }
}
