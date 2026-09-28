# Baseline: running a `.tflite` model on Android and iOS from a KMP module

Portable record of **which libraries this project uses to run TFLite inference, how they are wired, and what breaks**, so the same setup can be rebuilt in another app without re-deriving it.

Extracted from ScannerCatDogs on 2026-09-28. Everything below is in the repo and has been built; where something is unverified it says so.

- Library evaluation and rejected options: [RESEARCH.md](RESEARCH.md).
- The model's input/output interface: [MODEL_CONTRACT.md](MODEL_CONTRACT.md).

## 1. The stack in one table

| Layer | Android | iOS |
|---|---|---|
| Inference runtime | **LiteRT** `com.google.ai.edge.litert:litert:2.2.0` (Maven) | **TensorFlow Lite C 2.17.0** XCFramework + Kotlin/Native cinterop |
| API used | `CompiledModel` (Kotlin, from transitive `litert-api`) | `TfLiteInterpreter` C API (`c_api.h`) |
| Model file | same `.tflite`, in `androidMain/assets/`, read via `AssetManager` | same `.tflite`, copied into the app bundle, read via `NSBundle.pathForResource` |
| Camera | CameraX 1.6.2 `ImageAnalysis` (`RGBA_8888`, `KEEP_ONLY_LATEST`) | AVFoundation `AVCaptureVideoDataOutput` (`32BGRA`) |
| Preprocessing | `Bitmap` crop/rotate + `createScaledBitmap` | hand-written bilinear over raw bytes (`IosFramePreparation`) |
| UI | Compose Multiplatform 1.11.1, shared | same |
| Toolchain | AGP 9.0.1, Gradle 9.1.0, Kotlin 2.4.10, JVM target 11, compileSdk 36 / minSdk 24 | Xcode >= 26.4, `iosArm64` + `iosSimulatorArm64`, static framework |

One `.tflite` serves both platforms. There is no Core ML conversion and no second model file.

## 2. Module layout (forced by AGP 9)

```
settings.gradle.kts -> include(":shared"), include(":androidApp")
shared/     com.android.kotlin.multiplatform.library   <- KMP + Android library + iOS framework
androidApp/ com.android.application                    <- thin Android host
iosApp/     Xcode project                              <- thin iOS host
```

AGP 9 does **not** allow `org.jetbrains.kotlin.multiplatform` together with `com.android.application` / `com.android.library`. The shared module must use `com.android.kotlin.multiplatform.library`, and the Android app must be its own module. Do not try to collapse them.

## 3. Android

### 3.1 Version catalog (`gradle/libs.versions.toml`)

```toml
[versions]
litert = "2.2.0"
androidx-camerax = "1.6.2"

[libraries]
litert = { module = "com.google.ai.edge.litert:litert", version.ref = "litert" }
androidx-camera-core      = { module = "androidx.camera:camera-core",      version.ref = "androidx-camerax" }
androidx-camera-camera2   = { module = "androidx.camera:camera-camera2",   version.ref = "androidx-camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "androidx-camerax" }
androidx-camera-view      = { module = "androidx.camera:camera-view",      version.ref = "androidx-camerax" }
```

`shared/build.gradle.kts`:

```kotlin
androidMain.dependencies {
    api(libs.litert)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
}
```

`api` rather than `implementation` because the app module touches `Accelerator` when it constructs the classifier. Inference alone needs only `litert`.

Facts worth carrying over:

- `CompiledModel` lives in `com.google.ai.edge.litert:litert-api`, pulled in **transitively**. Declaring `litert` is enough; declaring `litert-api` by hand is not needed.
- The GPU accelerator is built in for Kotlin. No `litert-gpu` artifact, and **no hand-written `<uses-native-library android:name="libOpenCL.so">`** - LiteRT's own AAR manifest already declares it.
- The AAR ships `arm64-v8a`, `armeabi-v7a`, `x86_64`. **No 32-bit `x86`**, so a 32-bit x86 emulator cannot run it.
- Min API 23 (this project sits at 24).

### 3.2 `gradle.properties` - one load-bearing flag

```properties
android.uniquePackageNames=false
```

`litert` and `litert-api` both declare the namespace `com.google.ai.edge.litert`. In a **library** module that is only a warning; merging it into an **application** is a hard error and `:androidApp:processDebugMainManifest` fails. Both artifacts are required (`litert` ships `libLiteRt.so`, `litert-api` ships `CompiledModel`), so the check has to be downgraded. The remaining warning is expected.

### 3.3 Manifest - permissions LiteRT contributes that you probably do not want

LiteRT's manifest adds `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` for its AiPack model-download feature. An app that loads the model from assets uses none of them. `androidApp/src/main/AndroidManifest.xml` strips them:

```xml
<manifest xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" tools:node="remove" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" tools:node="remove" />
    <uses-permission android:name="android.permission.WAKE_LOCK" tools:node="remove" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" tools:node="remove" />
</manifest>
```

Drop the removals if AiPack is ever adopted.

### 3.4 The inference call

```kotlin
class AndroidCatDogClassifier(
    assets: AssetManager,
    modelAsset: String = ModelContract.DEFAULT_MODEL_ASSET,
    accelerator: Accelerator = Accelerator.CPU,
) : AutoCloseable {
    private val model = CompiledModel.create(assets, modelAsset, CompiledModel.Options(accelerator))
    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()
    private val input = FloatArray(ModelContract.INPUT_FLOAT_COUNT)

    fun classify(bitmap: Bitmap): Classification {
        writeInput(bitmap)
        inputBuffers[0].writeFloat(input)
        model.run(inputBuffers, outputBuffers)
        return Classification(outputBuffers[0].readFloat()[0])
    }

    override fun close() = model.close()
}
```

- `CompiledModel.create(assets, name, ...)` reads straight from `AssetManager`, so the model stays in `androidMain/assets/` - no copy to the files dir, no `MappedByteBuffer` juggling.
- Buffers are created **once** and reused; allocating per frame is what makes a live-camera path stutter.
- `Accelerator.CPU` is the default here. GPU needs a float32 model - a dynamic-range quantized one is CPU only.
- `close()` matters; the model holds native memory.

### 3.5 APK size

The debug APK went from ~18 MB to ~47 MB: LiteRT's three ABIs are ~24 MB of `.so`. Before shipping, use an ABI split or `abiFilters`; `arm64-v8a` alone covers essentially every modern phone.

## 4. iOS

There is no CocoaPods-free official package for LiteRT and no SwiftPM package, so the baseline is the **frozen but functional TensorFlow Lite C 2.17.0** XCFramework, fetched by Gradle and consumed through cinterop. No CocoaPods, no Podfile, no `cocoapods {}` block.

### 4.1 Gradle fetches the XCFramework (`shared/build.gradle.kts`)

```kotlin
val tensorFlowLiteCUrl =
    "https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
val tensorFlowLiteCSha256 = "9667b476015f136e5b332ce040e12822c4ac6d5c58947882ddc809cdff0fb99e"
val iosFrameworksDir = rootProject.layout.projectDirectory.dir("iosApp/Frameworks")
val tensorFlowLiteCXcframework = iosFrameworksDir.dir("TensorFlowLiteC.xcframework")
val isMacHost = System.getProperty("os.name").startsWith("Mac")

val downloadTensorFlowLiteC = tasks.register<Exec>("downloadTensorFlowLiteC") {
    group = "ios"
    enabled = isMacHost
    inputs.property("url", tensorFlowLiteCUrl)
    inputs.property("sha256", tensorFlowLiteCSha256)
    outputs.dir(tensorFlowLiteCXcframework)
    // curl -> shasum -a 256 -c - -> tar -xzf -> mv .../Frameworks/TensorFlowLiteC.xcframework into iosApp/Frameworks/
}
```

- The archive is ~80 MB, the SHA-256 is **checked**, and `iosApp/Frameworks/` is gitignored - the binary is not in git.
- `enabled = isMacHost` keeps Windows builds from trying; `outputs.dir(...)` makes it run once.
- The archive also contains `TensorFlowLiteCCoreML.xcframework` and `TensorFlowLiteCMetal.xcframework` if delegates are ever wanted; this baseline unpacks only the core one.

### 4.2 cinterop

`shared/src/nativeInterop/cinterop/TensorFlowLiteC.def` is the whole definition:

```
language = C
headers = c_api.h
package = tensorflow.lite.c
```

Wiring, per target, with the matching XCFramework slice:

```kotlin
listOf(
    iosArm64() to "ios-arm64",
    iosSimulatorArm64() to "ios-arm64_x86_64-simulator",
).forEach { (iosTarget, slice) ->
    val sliceDir = tensorFlowLiteCXcframework.dir(slice).asFile.absolutePath
    iosTarget.compilations.getByName("main").cinterops.create("TensorFlowLiteC") {
        definitionFile.set(project.file("src/nativeInterop/cinterop/TensorFlowLiteC.def"))
        compilerOpts("-I$sliceDir/TensorFlowLiteC.framework/Headers")
        tasks.named(interopProcessingTaskName).configure { dependsOn(downloadTensorFlowLiteC) }
    }
    iosTarget.binaries.all {
        linkerOpts("-F$sliceDir", "-framework", "TensorFlowLiteC", "-lc++")
    }
    iosTarget.binaries.framework {
        baseName = "Shared"
        isStatic = true
    }
}
```

Plus, in `gradle.properties`:

```properties
kotlin.mpp.enableCInteropCommonization=true
```

without which `iosMain` (a shared source set over both iOS targets) cannot see the cinterop API - only the per-target source sets could.

`-lc++` is required: the TFLite C library is C++ underneath. `linkerOpts` on `binaries.all` covers **test executables too**, which is what lets `ReferenceImageIosTest` link.

### 4.3 Xcode side (`iosApp/Configuration/Config.xcconfig`)

```
TFLITE_XCFRAMEWORK=$(SRCROOT)/Frameworks/TensorFlowLiteC.xcframework
FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]=$(inherited) $(TFLITE_XCFRAMEWORK)/ios-arm64
FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]=$(inherited) $(TFLITE_XCFRAMEWORK)/ios-arm64_x86_64-simulator
OTHER_LDFLAGS=$(inherited) -framework TensorFlowLiteC -lc++
```

Per-SDK search paths, because device and simulator need different slices. The app target also runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode` as its first build phase.

`Info.plist` must carry `NSCameraUsageDescription`. Without it iOS **kills the app** when the camera is touched, rather than denying permission.

### 4.4 Shipping the model in the bundle

A `Copy Model Files` shell build phase (runs before `Sources`, `alwaysOutOfDate = 1`) copies the Android assets into the bundle, so both platforms always ship identical model files:

```sh
set -eu
DESTINATION="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
mkdir -p "$DESTINATION"
cp "$SRCROOT/../shared/src/androidMain/assets/"*.tflite "$DESTINATION/"
```

Read back with:

```kotlin
fun bundledModelPath(asset: String): String? =
    NSBundle.mainBundle.pathForResource(asset.substringBeforeLast('.'), asset.substringAfterLast('.', ""))
```

For the simulator **test** binary there is no bundle, so Gradle hands the assets directory to the test process instead:

```kotlin
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_SCANNER_ASSETS_DIR", project.file("src/androidMain/assets").absolutePath)
}
```

`SIMCTL_CHILD_` is the prefix `simctl` requires to forward an environment variable into the simulated process; the test reads it as `SCANNER_ASSETS_DIR`.

### 4.5 The C API call sequence

```kotlin
model = TfLiteModelCreateFromFile(modelPath) ?: error(...)
options = TfLiteInterpreterOptionsCreate() ?: error(...)
TfLiteInterpreterOptionsSetNumThreads(options, threads)      // 2 here
interpreter = TfLiteInterpreterCreate(model, options) ?: error(...)
check(TfLiteInterpreterAllocateTensors(interpreter) == kTfLiteOk)

inputSize = TfLiteTensorDim(TfLiteInterpreterGetInputTensor(interpreter, 0), 1)   // read, do not hardcode

// per frame:
val inputTensor = TfLiteInterpreterGetInputTensor(interpreter, 0)
input.usePinned { TfLiteTensorCopyFromBuffer(inputTensor, it.addressOf(0), (input.size * Float.SIZE_BYTES).toULong()) }
check(TfLiteInterpreterInvoke(interpreter) == kTfLiteOk)
output.usePinned { TfLiteTensorCopyToBuffer(TfLiteInterpreterGetOutputTensor(interpreter, 0), it.addressOf(0), Float.SIZE_BYTES.toULong()) }

// teardown, in this order:
TfLiteInterpreterDelete(interpreter); TfLiteInterpreterOptionsDelete(options); TfLiteModelDelete(model)
```

Notes that cost time to learn:

- Types arrive as `cnames.structs.TfLiteModel` / `TfLiteInterpreterOptions` / `TfLiteInterpreter` (opaque C structs), and every file touching them needs `@OptIn(ExperimentalForeignApi::class)`.
- Every call returns a status; `kTfLiteOk` is the only success. Failing to `AllocateTensors` before `Invoke` is the classic crash.
- `usePinned { it.addressOf(0) }` is how a Kotlin `FloatArray` is handed to C without a copy. Reuse the array across frames.
- Read the input size from `TfLiteTensorDim` rather than trusting a constant - it catches a swapped model immediately.
- Deletion order is interpreter, then options, then model.

## 5. The shared layer that makes both sides agree

The contract lives in `commonMain` so neither platform can drift:

```kotlin
object ModelContract {
    const val INPUT_SIZE = 224                 // whatever the current contract says
    const val CHANNELS = 3
    const val INPUT_FLOAT_COUNT = INPUT_SIZE * INPUT_SIZE * CHANNELS
    const val DOG_THRESHOLD = 0.5f
    const val DEFAULT_MODEL_ASSET = "..."
    const val OPTIMIZED_MODEL_ASSET = "..."
}

data class Classification(val dogProbability: Float) {
    val label: Label = if (dogProbability > ModelContract.DOG_THRESHOLD) Label.DOG else Label.CAT
    val confidence: Float = if (label == Label.DOG) dogProbability else 1f - dogProbability
}
```

Camera and inference sit behind an `expect`/`actual` `Scanner`; the UI is pure Compose and never sees a platform type. Each platform has exactly two inference-related files: the classifier and the frame preparation.

### Preprocessing rules that must match on both platforms

Inference-time preprocessing has to reproduce training exactly. In this project that means: apply sensor rotation, center-crop a square (live camera only), resize to `INPUT_SIZE` **bilinear**, drop alpha, write float32 RGB interleaved, and **feed raw 0-255 values** because the model contains its own `Rescaling` layer. Dividing by 255 "to normalize" returns ~0.55 for every image.

Platform-specific traps found the hard way:

| Trap | Detail |
|---|---|
| Color order | Android `RGBA_8888` is already RGB; iOS camera frames are `32BGRA` and must swap B and R. `ChannelOrder` in `IosFramePreparation` encodes this. |
| iOS resize | **Do not use `CGContextDrawImage` scaling.** Every `CGInterpolationQuality` was off by 5e-4 to 9e-3 against the reference; the hand-written bilinear with half-pixel centres lands at 2e-6. |
| CoreImage in tests | `CIContext.createCGImage` returns null in the simulator test binary, even with the software renderer. One more reason the iOS path works on raw bytes. |
| Double rotation | Android `ImageProxy.toBitmap()` returns the frame **unrotated** (`rotationDegrees` is metadata); iOS buffers are also left unrotated and `videoRotationAngle` is **not** set on the data-output connection. Rotate exactly once, in preprocessing. |
| Orientation sensitivity | On this model a sideways dog reads CAT at 0.05. Any orientation bug looks like "wrong animal, high confidence", never like low confidence. |
| Premultiplied alpha | `BitmapFactory` premultiplies RGB by alpha; OpenCV (which produced the reference) does not. They agree only while the image is fully opaque. A reference image with real transparency needs `inPremultiplied = false`, and `Bitmap.createScaledBitmap` rejects unpremultiplied bitmaps. |
| EXIF | `BitmapFactory` ignores EXIF orientation. A photo shot on a phone almost certainly carries one. Check before swapping a reference image. |
| Center-crop vs squash | Training resized the whole image without preserving aspect; a camera path that crops a square first shifts scores measurably (0.0023 -> 0.277 on one image: same verdict, far less margin). Test both before blaming the model. |

## 6. Verification

Never trust a port until a fixed image produces the published number on both platforms.

| Command | What it proves |
|---|---|
| `./gradlew :shared:connectedAndroidDeviceTest` | `ReferenceImageTest`: bundled images through the app's own preprocessing, label plus +/-0.02 against the contract's reference values. Needs a device or emulator. The task is `connectedAndroidDeviceTest`, not `androidDeviceTest`. |
| `./gradlew :shared:iosSimulatorArm64Test` | `ReferenceImageIosTest` (same reference values, same tolerance) and `IosFramePreparationTest` (pins the rotation direction). macOS only. |
| `./gradlew :shared:testAndroidHostTest` | `ClassificationTest`: output semantics - the single output is the dog score, `> 0.5` is dog, `confidence` is `1 - score` for a cat. No device needed. |
| `./gradlew :androidApp:assembleDebug` | Android build. |

A tolerance of `+/-0.02` passes even when something is subtly wrong. The healthy signal is agreement to **~1e-04** on the float32 model; if deltas move into the 1e-02 range the chain broke - suspect color order, scaling, or the resize - even though the assertion still passes. Both tests log every measured value, so a failure shows the drift.

A debugging aid worth reimplementing: `AndroidCatDogClassifier.lastInputAsBitmap()` rebuilds the exact tensor last fed to the model from the float array. Dumping it as base64 to logcat (scoped storage blocks adb from reading app files) settles any "is it the app or the model" question in one step.

## 7. Porting checklist

1. Split the project: `shared` with `com.android.kotlin.multiplatform.library`, a separate `com.android.application` module, an `iosApp` Xcode project.
2. `gradle.properties`: `android.uniquePackageNames=false`, `kotlin.mpp.enableCInteropCommonization=true`.
3. Catalog: `litert` 2.2.0 (+ CameraX if there is a camera). `api(libs.litert)` in `androidMain`.
4. Strip LiteRT's five AiPack permissions in the app manifest.
5. Put the `.tflite` in `shared/src/androidMain/assets/`; load with `CompiledModel.create(assets, name, Options(Accelerator.CPU))`.
6. Add the `downloadTensorFlowLiteC` task (URL + SHA-256 above), the `.def` file, the per-target cinterop and `linkerOpts("-F<slice>", "-framework", "TensorFlowLiteC", "-lc++")`.
7. `Config.xcconfig`: per-SDK `FRAMEWORK_SEARCH_PATHS` and `OTHER_LDFLAGS`. Add the `Copy Model Files` build phase. Add `NSCameraUsageDescription` if a camera is used.
8. Put `INPUT_SIZE`, channel count, threshold and asset names in a `commonMain` contract object; keep `expect`/`actual` down to classifier + frame preparation.
9. Write the reference test on both platforms **before** wiring the camera, with values published by the ML side.
10. Only then add the live camera: throttle the frames (~120 ms here), smooth the displayed score (EMA 0.35), and stop the analyzer on pause rather than discarding frames.

## 8. Options considered and rejected

Full reasoning in [RESEARCH.md](RESEARCH.md); the short version, so it is not re-litigated:

| Option | Why not |
|---|---|
| Core ML on iOS (convert with `coremltools`) | Would use the Neural Engine, but needs a second model file and a conversion step in the ML project. Still open as a later optimization, not as the baseline. |
| `TensorFlowLiteObjC` / `TensorFlowLiteSwift` pods | Same binary as used here, but drags in CocoaPods, whose trunk goes read-only on 2026-12-02 while Kotlin 2.4 moves toward SwiftPM. The direct XCFramework + cinterop route needs no package manager at all. |
| LiteRT 2.2.0 C API on iOS | Current runtime and symmetrical with Android, but no official CocoaPods/SwiftPM package, and the Metal accelerator has to be `dlopen`ed from a pinned URL. More moving parts than TFLite C 2.17.0. |
| Play services `play-services-tflite-java` | Runtime shipped by Play services (smaller APK) but Interpreter-only, and ties inference to Play availability. |
| KMP wrappers: CameraK, peekaboo, moko-tensorflow, kflite | CameraK delivers JPEG frames (wrong shape for a per-frame tensor); peekaboo and moko-tensorflow are stale; kflite is alpha. Do not reach for them without re-reading RESEARCH.md. |
| `litert-gpu`, `litert-support`, `litert-metadata` (1.4.2) | Belong to the older Interpreter-based line. Unnecessary with `CompiledModel`. |
| `androidx.core:core-ktx` 1.19.0 | Requires compileSdk 37 / AGP 9.1+; fails `checkDebugAarMetadata` on compileSdk 36 + AGP 9.0.1. `ContextCompat` already arrives transitively through CameraX. |

## 9. Known limits of this baseline

- The iOS **live camera path has not been verified on a physical iPhone**; the simulator has no camera. Device build, link, and the reference test on the simulator all pass.
- TensorFlow Lite C 2.17.0 (2024-07-29) is the last stable release of that line. It works, but it is frozen; LiteRT is where new work goes.
- No GPU/Metal/CoreML delegate is enabled on either platform. CPU inference measures ~2 ms per frame here, so the frame conversion dominates, not the model.
- The `.tflite` in the bundle is readable by anyone who unzips the app. See [MODEL_PROTECTION.md](MODEL_PROTECTION.md).
