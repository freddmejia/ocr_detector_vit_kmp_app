# Traceability: libraries added according to Baseline.md

Record of the changes made to bring this project in line with [Baseline.md](Baseline.md): what was changed, where, why, and how it was verified.

- Date: 2026-09-28
- Source of truth: `shared/agents/Baseline.md` (TFLite inference in a KMP module, extracted from ScannerCatDogs)
- Context: `shared/agents/Pipeline.md` (license-plate detector + OCR pipeline)
- Scope: libraries and the build/manifest settings they need. No application code was written.

## 1. Summary

| # | File | Change | Baseline ref |
|---|---|---|---|
| 1 | `gradle/libs.versions.toml` | Added `litert` 2.2.0 and CameraX 1.6.2 (4 artifacts) | §3.1 |
| 2 | `shared/build.gradle.kts` | `androidMain`: `api(libs.litert)` + 4 CameraX `implementation` | §3.1 |
| 3 | `shared/build.gradle.kts` | `downloadTensorFlowLiteC` task, per-target cinterop, `linkerOpts` | §4.1, §4.2 |
| 4 | `shared/src/nativeInterop/cinterop/TensorFlowLiteC.def` | New file | §4.2 |
| 5 | `gradle.properties` | `android.uniquePackageNames=false` | §3.2 |
| 6 | `gradle.properties` | `kotlin.mpp.enableCInteropCommonization=true` | §4.2 |
| 7 | `androidApp/src/main/AndroidManifest.xml` | `CAMERA` permission + removal of LiteRT's 5 AiPack permissions | §3.3 |
| 8 | `iosApp/Configuration/Config.xcconfig` | Per-SDK `FRAMEWORK_SEARCH_PATHS`, `OTHER_LDFLAGS` | §4.3 |
| 9 | `iosApp/iosApp/Info.plist` | `NSCameraUsageDescription` | §4.3 |
| 10 | `.gitignore` | `iosApp/Frameworks/` | §4.1 |

## 2. Changes in detail

### 2.1 Version catalog - `gradle/libs.versions.toml`

Added under `[versions]`:

```toml
androidx-camerax = "1.6.2"
litert = "2.2.0"
```

Added under `[libraries]`:

```toml
litert = { module = "com.google.ai.edge.litert:litert", version.ref = "litert" }
androidx-camera-core = { module = "androidx.camera:camera-core", version.ref = "androidx-camerax" }
androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "androidx-camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "androidx-camerax" }
androidx-camera-view = { module = "androidx.camera:camera-view", version.ref = "androidx-camerax" }
```

Why: LiteRT is the Android inference runtime (`CompiledModel`, with `litert-api` pulled in transitively). CameraX provides the live camera frames.

### 2.2 Android dependencies - `shared/build.gradle.kts`

```kotlin
androidMain.dependencies {
    // existing: compose.uiToolingPreview, compose.uiTooling
    api(libs.litert)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
}
```

Why `api`: the app module will reference `Accelerator` when it constructs the classifier (Baseline §3.1).

### 2.3 iOS runtime - `shared/build.gradle.kts`

- **`downloadTensorFlowLiteC`** (group `ios`): downloads TensorFlowLiteC 2.17.0 from `dl.google.com`, verifies SHA-256 `9667b476...fb99e` with `shasum -a 256 -c`, extracts it and moves `TensorFlowLiteC.xcframework` into `iosApp/Frameworks/`.
  - `enabled = isMacHost`, so it is skipped on Windows.
  - `outputs.dir(...)`, so it runs only once.
  - URL, hash and destination go to the shell script as environment variables. The script finds the `.xcframework` inside the archive with `find`, because the baseline does not give the archive's internal layout.
- **Targets**: `iosArm64` -> slice `ios-arm64`, `iosSimulatorArm64` -> slice `ios-arm64_x86_64-simulator`.
- **cinterop `TensorFlowLiteC`** on each target's `main` compilation, with `-I<slice>/TensorFlowLiteC.framework/Headers`. Its processing task depends on `downloadTensorFlowLiteC`.
- **`binaries.all { linkerOpts("-F<slice>", "-framework", "TensorFlowLiteC", "-lc++") }`**: `-lc++` is required because TFLite C is C++ underneath. `binaries.all` also covers test executables.
- **Framework**: unchanged (`baseName = "Shared"`, `isStatic = true`), now declared inside the same per-target loop.

### 2.4 cinterop definition - `shared/src/nativeInterop/cinterop/TensorFlowLiteC.def`

```
language = C
headers = c_api.h
package = tensorflow.lite.c
```

### 2.5 `gradle.properties`

```properties
android.uniquePackageNames=false
kotlin.mpp.enableCInteropCommonization=true
```

- `android.uniquePackageNames=false`: `litert` and `litert-api` both declare the namespace `com.google.ai.edge.litert`. Without this flag `:androidApp:processDebugMainManifest` fails. The warning that remains is expected.
- `kotlin.mpp.enableCInteropCommonization=true`: without it, `iosMain` cannot see the cinterop API. Only the per-target source sets could.

### 2.6 Android manifest - `androidApp/src/main/AndroidManifest.xml`

- Added the `xmlns:tools` namespace.
- Added `android.permission.CAMERA`.
- Removed with `tools:node="remove"`: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`. LiteRT adds these for AiPack model downloads, which this app does not use because models ship in assets. Drop the removals if AiPack is ever adopted.

### 2.7 Xcode configuration - `iosApp/Configuration/Config.xcconfig`

```
TFLITE_XCFRAMEWORK=$(SRCROOT)/Frameworks/TensorFlowLiteC.xcframework
FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]=$(inherited) $(TFLITE_XCFRAMEWORK)/ios-arm64
FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]=$(inherited) $(TFLITE_XCFRAMEWORK)/ios-arm64_x86_64-simulator
OTHER_LDFLAGS=$(inherited) -framework TensorFlowLiteC -lc++
```

Why: `Shared` is a static framework, so the app target has to link TensorFlowLiteC itself. Device and simulator need different slices.

### 2.8 `iosApp/iosApp/Info.plist`

```xml
<key>NSCameraUsageDescription</key>
<string>The camera is used to read license plates.</string>
```

Why: without it, iOS kills the app when the camera is accessed instead of denying permission.

### 2.9 `.gitignore`

```
iosApp/Frameworks/
```

Why: the ~80 MB XCFramework is downloaded by Gradle and must not be committed.

## 3. Deviations from the baseline

| Item | Baseline | This project | Reason |
|---|---|---|---|
| AGP | 9.0.1 | 9.1.1 | Kept the project's newer version |
| Kotlin | 2.4.10 | 2.4.20 | Kept the project's newer version |
| compileSdk | 36 | 37 | Kept the project's newer version |
| Compose Multiplatform | 1.11.1 | 1.12.1 | Kept the project's newer version |
| `androidx.core:core-ktx` 1.19.0 | Rejected | Left in the catalog | The baseline rejected it only because it requires compileSdk 37 / AGP 9.1+, which this project already meets |

## 4. Verification

| Check | Result |
|---|---|
| `./gradlew :androidApp:assembleDebug` | **Passed** (run with `JAVA_HOME` = Android Studio's bundled JDK, `C:\Program Files\Android\Android Studio\jbr`, because `JAVA_HOME` is not set in the shell) |
| Merged manifest (`androidApp/build/intermediates/merged_manifest/debug/...`) | Only `CAMERA` plus the auto-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. The 5 AiPack permissions are gone. |
| iOS: `downloadTensorFlowLiteC`, cinterop, framework link, Xcode build | **Not verified.** It needs a macOS host, and the task is disabled on Windows. |

Issues found and fixed along the way: `gradle.properties` and `Config.xcconfig` had no trailing newline, so the first appended line was merged into the last existing line (`android.useAndroidX=true# ...`). This broke the Gradle build, and both files were corrected.

## 5. Not done (out of scope for "libraries")

- Xcode build phases: `./gradlew :shared:embedAndSignAppleFrameworkForXcode` and `Copy Model Files` (Baseline §4.3, §4.4).
- `KotlinNativeSimulatorTest` environment `SIMCTL_CHILD_SCANNER_ASSETS_DIR` (Baseline §4.4). Add it when there are model assets and iOS reference tests.
- ABI split / `abiFilters` to reduce APK size (Baseline §3.5).
- Any application code: classifiers, frame preparation, `ModelContract`, `expect`/`actual` scanner.

## 6. Open items

1. **Signature runners.** `Pipeline.md` requires running TFLite *signatures* (the OCR has `encoder` and `decoder`). Before implementing, confirm that LiteRT `CompiledModel` exposes signature-keyed buffers on Android and that TFLite C 2.17.0's `c_api.h` exposes the signature-runner API on iOS.
2. **Model size.** The detector is ~125 MB and the OCR ~394 MB. Shipping them as APK assets and copying them into the iOS bundle needs a delivery decision (for example Play Asset Delivery or on-demand download).
3. **Camera.** CameraX and the camera permissions were added on the assumption of live capture. `Pipeline.md` describes photo input only. Remove them if the app will not use the camera.
4. **iOS verification** on macOS: run `./gradlew :shared:downloadTensorFlowLiteC`, `:shared:iosSimulatorArm64Test` and an Xcode build.

Status of these items after Part 2: 1 resolved on Android (the classic `Interpreter` runs signatures by name, see section 9); 2 decided for development (models in APK assets); 3 kept (live camera is the chosen flow); 4 still open.

---

# Part 2: Pipeline.md implementation (Android)

- Date: 2026-09-28
- Source of truth: `shared/agents/Pipeline.md` and `notebooks/Pipeline_TFLite.ipynb` of the ML project.
- Decisions taken with the user: models ship in `androidMain/assets` (already placed by the user); the live flow runs **detector + OCR** on the guide rectangle; the top label shows **both** confidences; UI text in **English**.

## 7. What was built

### 7.1 Shared pipeline (`commonMain/.../pipeline`, no platform code)

| File | Content | Pipeline.md |
|---|---|---|
| `RgbImage.kt` | RGB image (0xRRGGBB), `PixelRect`, `Box`, crop | 9 |
| `BilinearResize.kt` | Port of Pillow `resize(BILINEAR)`: antialias when shrinking, 8-bit fixed point. Byte-exact with Pillow 10.4 | 4.2, 6.2 |
| `Preprocessing.kt` | `Normalization`, `RgbImage.toNchw()`: resize without keeping aspect, normalize, NCHW | 4.2, 6.2 |
| `ModelConfigs.kt` | `ModelFiles` (asset paths), `DetectorConfig`, `OcrConfig`, `Vocabulary`, `normalizePlateText`. Parsed with kotlinx-serialization `JsonElement` (no compiler plugin) | 2.4, 6.4 |
| `TfliteModel.kt` | Runtime interface: signatures, input/output names and shapes, `run` into caller-owned arrays | 9 |
| `PlateDetector.kt` | Validates shapes against the JSON at load; outputs told apart by shape; sigmoid, corners, threshold, sort (no NMS); crop with padding using the reference truncation | 4 |
| `PlateOcr.kt` | Validates both signatures, the ids input, the features and the vocabulary size; greedy loop reading row `t`; strict argmax; OCR confidence = lowest softmax probability of the chosen tokens (uncalibrated, 6.5) | 6 |
| `LicensePlatePipeline.kt` | `PipelineParams` (0.4 / 0.08 / 0.15 / 60), `PlateReading`, `PipelineResult`, orchestration, `maxPlatesToRead` (1 in live mode), `readPlate` for crops | 7, 8 |

### 7.2 Android (`androidMain`)

| File | Content |
|---|---|
| `pipeline/AndroidTfliteModel.kt` | `TfliteModel` on LiteRT's `org.tensorflow.lite.Interpreter` (shipped inside `litert` 2.2.0). Models memory-mapped from the APK; one direct buffer per tensor, reused. `createLicensePlatePipeline()` loads and cross-validates everything. Threads: `min(cores, 4)` |
| `scanner/FrameCropper.kt` | View -> upright visible frame (FILL_CENTER, using the frame's `cropRect` = PreviewView viewport) -> buffer. Crops the guide at full frame resolution and rotates exactly once |
| `scanner/ScannerViewModel.kt` | `LifecycleCameraController` (analysis only, RGBA_8888, KEEP_ONLY_LATEST, target 1920x1440 4:3). Model loading and inference on one thread, which serializes interpreter access. Results, history, lens, zoom, torch |
| `ScannerRoute.android.kt` | Camera permission flow (request, rationale, Settings); `PreviewView` in `AndroidView` (pinch-to-zoom and tap-to-focus come from the controller); keeps the screen on while scanning |

### 7.3 Shared UI (`commonMain/.../scanner`)

| File | Content |
|---|---|
| `ScannerState.kt` | `ScanRegion`: single source of the guide rectangle (2:1), used by the overlay **and** the analyzer. UI state and `ScannerActions` |
| `ScannerScreen.kt` | Top confidence header (Detection / Reading meters colored by level, status, timing); hint above the guide; result card below it (plate-styled text, or guidance: no plate / too far / unclear); history chips; zoom presets (the active one shows the exact ratio); control bar (flashlight, big Start/Stop, switch camera); model loading/error overlay; permission screen; haptic feedback on a read |
| `ScanOverlay.kt` | Dimmed surroundings with a rounded cutout, corner brackets, frame color by state, sweep line while scanning, detected plate box (mirrored for the front camera) |
| `ScannerIcons.kt`, `ScannerTheme.kt`, `ScannerPreviews.kt` | Inline Material icons (no icons artifact), dark theme and formatting, `@Preview`s with sample state |
| `App.kt`, `ScannerRoute.ios.kt` | `expect fun ScannerRoute()`; iOS shows a "not available yet" placeholder |

## 8. Build changes

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | `kotlinx-coroutines` 1.11.0, `kotlinx-serialization-json` 1.11.0, `androidx.test:runner` 1.7.0 |
| `shared/build.gradle.kts` | `commonMain`: coroutines, serialization-json. `androidMain`: `activity-compose`. `androidDeviceTest`: test runner + ext-junit. `androidResources`: `noCompress += "tflite"` and ignore `trocr_placas.tflite` (device-test APK) |
| `androidApp/build.gradle.kts` | Same `noCompress` / ignore rules for the app APK |
| `androidApp/.../MainActivity.kt` | Removed the template `@Preview`, which cannot render the camera and ViewModel; previews now live in `ScannerPreviews.kt` |
| Removed | Template tests `SharedCommonTest.kt`, `SharedLogicAndroidHostTest.kt` |
| Added test data | `shared/src/androidDeviceTest/assets/reference/`: the 9 images of `data/vehicles` and `lecturas_tflite.csv` |

`trocr_placas.tflite` (1.5 GB float32) stays on disk in `assets/ocr` but is excluded from both APKs (Pipeline.md 2.1). Both APKs are ~565 MB with the models stored uncompressed.

## 9. Deviations and findings

| Item | Detail |
|---|---|
| `Interpreter` instead of `CompiledModel` (Baseline 3.4) | `CompiledModel` cannot list signature input/output names, and `readFloat()` allocates a new array on every call (3.2 MB per decoder step). `Interpreter` exposes names and shapes (the validation Pipeline.md 3 asks for) and fills reusable buffers. Same `litert` artifact and `libLiteRt.so` |
| OCR runs **without XNNPACK** | With XNNPACK the process reached ~2.9 GB of anonymous memory and was killed by the low-memory killer. The detector keeps XNNPACK (~215 MB; 0.7 s on the emulator vs 3.5 s without) |
| **The "int8" OCR is int8 on disk only; it runs as float32** | Each int8 weight goes through a single-input `ADD` that outputs float32 before `FULLY_CONNECTED` / `EMBEDDING_LOOKUP`. The runtime materializes every weight as float32: **~1.4 GB of RAM** for the OCR whatever the options (XNNPACK doubles it; the `CompiledModel` XNNPACK flags and weight cache did not help). Process peak ~1.7 GB. The fix belongs to the ML project: re-export with dynamic-range quantization (hybrid ops that consume int8 weights directly) |
| Live mode reads 1 plate per frame | `maxPlatesToRead = 1` on the guide crop keeps latency down; `run(image)` with defaults is the full pipeline (every plate) |
| Photo / EXIF path | Not implemented: the app is camera-only for now (frames carry no EXIF; rotation comes from `rotationDegrees`) |

## 10. Verification

| Check | Result |
|---|---|
| `:shared:testAndroidHostTest` (25 tests) | **Passed.** 10.1 normalization and NCHW; Pillow resize byte-exact (full 17x11 array + 5 checksums including 576x576); 10.2 postprocessing and crop; 10.3 vocabulary (inline and the real `vocabulario.json`, 50 265 tokens); greedy loop with a fake model (row t, padding, eos, 15-step limit, mismatched config rejected); SHA-256 of the 5 bundled files = Pipeline.md 2.1; `ScanRegion` geometry |
| Device tests on emulator Pixel_10a (API 37, x86_64, 4 GB) | **23/23 passed.** `ReferencePipelineTest` reproduces the whole 10.4 table: same plate count, scores equal to 3 decimals, same boxes, same texts `NZZ626`, `CUL718`, `LJ73`, `GRF474`. Detector ~0.7 s, OCR ~1.8 s per plate (emulator on a desktop CPU) |
| App on emulator | Launches; models load in ~0.7 s; live loop ~0.75 s per frame with no plate; Start/Stop, camera switch, zoom presets and the flashlight button work. The crop fed to the model was dumped once and matches the guide rectangle exactly (upright, same framing) |
| Physical device (Redmi 9 M2004J19C, Android 12, 3.7 GB) | **Not run yet**: MIUI rejected the adb install (`INSTALL_FAILED_USER_RESTRICTED`; "Install via USB" must be enabled on the phone) |
| A real plate through the live camera | **Not verified**: the emulator cameras show a virtual room, no plates |
| iOS | Not implemented (placeholder screen) |

## 11. Open items

1. **Re-export the OCR with dynamic-range quantization** (section 9). With the current file the app needs ~1.7 GB of RAM, which is tight on a 4 GB phone.
2. Measure speed and memory on the real phone. Expect it to be several times slower than the emulator; a slow OCR turns "real time" into one reading every few seconds.
3. Model delivery: a 565 MB APK is fine for development; Play needs download-on-first-use or Play Asset Delivery (Pipeline.md 2.3).
4. ABI split / `abiFilters` (Baseline 3.5).
5. iOS implementation.
