@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.detectorlicenseplateocr.pipeline

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceGetModel
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGColorSpaceRetain
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetColorSpace
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceModelRGB
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIImage

/** Folder given to the simulator test process by Gradle (`SIMCTL_CHILD_<name>` in shared/build.gradle.kts). */
internal fun testDir(name: String): String =
    NSProcessInfo.processInfo.environment[name] as? String
        ?: error("$name is not set: run the iOS tests through Gradle (./gradlew :shared:iosSimulatorArm64Test)")

internal fun referenceImages(): List<String> {
    val dir = testDir("REFERENCE_DIR")
    return NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
        .map { it as String }
        .filter { it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
        .sorted()
}

/**
 * Decodes a reference image to RGB like the reference (Pillow): stored orientation (EXIF ignored, as Android's
 * `BitmapFactory` does) and no color management, so the pixels are drawn in the image's own RGB color space.
 */
internal fun loadReferenceImage(name: String): RgbImage {
    val path = "${testDir("REFERENCE_DIR")}/$name"
    val image = UIImage.imageWithContentsOfFile(path)?.CGImage ?: error("Cannot decode $path")
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    val own = CGImageGetColorSpace(image)
    val colorSpace = if (own != null && CGColorSpaceGetModel(own) == kCGColorSpaceModelRGB) {
        CGColorSpaceRetain(own)
    } else {
        CGColorSpaceCreateDeviceRGB()
    }
    val bytes = ByteArray(width * height * 4)
    bytes.usePinned { pinned ->
        val context = CGBitmapContextCreate(
            pinned.addressOf(0), width.convert(), height.convert(), 8u, (width * 4).convert(), colorSpace,
            CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        ) ?: error("Cannot create a bitmap context for $name")
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
        CGContextRelease(context)
    }
    CGColorSpaceRelease(colorSpace)
    // Memory layout R, G, B, X.
    val pixels = IntArray(width * height) { i ->
        val o = i * 4
        (bytes[o].toInt() and 0xFF shl 16) or (bytes[o + 1].toInt() and 0xFF shl 8) or (bytes[o + 2].toInt() and 0xFF)
    }
    return RgbImage(width, height, pixels)
}
