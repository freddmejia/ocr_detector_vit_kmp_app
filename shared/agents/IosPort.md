# iOS: llevar el lector de placas (detector + fast-plate-ocr) a iOS

Encargo para una sesión de Claude Code en un **Mac** (con Xcode). Este documento es autocontenido: explica qué se pidió,
qué está ya hecho en el código compartido y qué falta en iOS, en orden, con criterios para darlo por terminado.

Escrito el 2026-10-12 desde Windows, donde no se puede compilar ni ejecutar código iOS: **nada de lo de iOS está
probado**. Lo que se afirma del código compartido sí se ha compilado y probado con los tests de host de Android.

Documentos relacionados, en esta misma carpeta:

- [Pipeline.md](Pipeline.md): contrato completo del pipeline (detector, recorte, OCR). Las secciones 2.1, 4.5 y 7 ya
  reflejan los cambios descritos aquí.
- [Baseline.md](Baseline.md): cómo se ejecuta un `.tflite` en iOS desde KMP (TensorFlow Lite C + cinterop), con las
  trampas encontradas en otro proyecto. **Leer la sección 4 y la tabla de trampas de la 5 antes de empezar.**
- [Traceability.md](Traceability.md): librerías y ajustes de build ya añadidos.

---

## 1. Qué se pidió

Dos peticiones del usuario, hechas y probadas en Android:

1. *"Con este conocimiento, el conocimiento que está en el proyecto, ¿puedes cambiar la config para Android? Ya he
   puesto el modelo y el config.yaml dentro de la app KMP."*
   El usuario reentrenó fast-plate-ocr (`cct_xs`, cuaderno `FastPlateOCR_Placas.ipynb` del repo de entrenamiento) y
   copió a `shared/src/androidMain/assets/ocr/` el `plate_ocr.tflite` nuevo y su `plate_config.yaml`.
2. *"He estado observando que las placas de algunos vehículos tienen unos caracteres a los lados, y a veces el detector
   los deja fuera. Sería bueno poner un ancho y alto dinámico para que el bounding box sea grande como el de las
   placas, no pequeño."*

Ahora hay que conseguir que **la app iOS haga lo mismo**. Como todo lo anterior se implementó en `commonMain`, en iOS
no hay que reimplementar esas dos funciones: hay que construir la parte de plataforma que todavía no existe (sección 3).

## 2. Lo que ya está hecho (código compartido, sirve tal cual para iOS)

### 2.1 Config del OCR desde `plate_config.yaml` (petición 1)

- `ModelFiles.FAST_OCR_CONFIG = "ocr/plate_config.yaml"` y `ModelFiles.FAST_OCR_MODEL = "ocr/plate_ocr.tflite"`
  (`shared/src/commonMain/.../pipeline/ModelConfigs.kt`).
- `FastOcrConfig.parse(yaml)` lee el YAML exportado con el modelo mediante `parseYamlScalars`, un parser mínimo de
  claves `clave: valor` de primer nivel (se salta comentarios y la lista multilínea `plate_regions`). No hay librería de
  YAML en el proyecto y no hace falta.
- Rechaza lo que el pipeline no sabe hacer: `image_color_mode` ≠ `rgb`, `keep_aspect_ratio: true`,
  `interpolation` ≠ `linear`.
- El `plate_ocr_config.json` escrito a mano se eliminó. **No hay `vocabulario.json` para este OCR:** el vocabulario
  es el campo `alphabet` del YAML.

Cómo funciona el modelo, para no tener que redescubrirlo:

| | Valor |
|---|---|
| Entrada | float32 NHWC `[1, 64, 128, 3]`, RGB con valores **0-255 sin normalizar** (el `1/255` va dentro del modelo) |
| Salida | `plate`, float32 `[1, 10, 37]`, ya con softmax |
| Significado | 10 posiciones de la placa (`max_plate_slots`) × 37 caracteres (`alphabet` = `0-9`, `A-Z`, `_`) |
| Decodificación | por fila: `argmax` → `alphabet[idx]`; se quitan los `_` (relleno); confianza = mínimo de los máximos |
| Firma | una sola (`serving_default`); el nombre de la entrada **no es fijo** (en este export es `input_layer_2`) |

`FastPlateOcr` (`commonMain/.../pipeline/FastPlateOcr.kt`) ya hace todo esto y valida las formas contra el YAML al
construirse. Solo necesita un `TfliteModel` que funcione.

### 2.2 Recorte con proporción de placa (petición 2)

- `PipelineParams.cropAspectRatio: Double? = 2.0` (`commonMain/.../pipeline/LicensePlatePipeline.kt`).
- `plateCropRect` (`commonMain/.../pipeline/PlateDetector.kt`) agranda la caja del detector **alrededor de su centro**
  hasta ancho/alto = 2.0 (nunca la reduce) y después aplica el margen de siempre (8 % horizontal, 15 % vertical).
  Una caja estrecha (caracteres laterales fuera) crece a lo ancho; una baja crece a lo alto. `null` lo desactiva.
- 2.0 = entrada del OCR (128×64) ≈ mediana de los recortes de entrenamiento colombianos (1,94; placa de 33×17 cm).
- Documentado en `Pipeline.md` 4.5 (paso 0) y en la tabla de parámetros.

**En iOS no hay que hacer nada para esto**, siempre que el pipeline se construya con `PipelineParams()` por defecto.

### 2.3 Tests que ya cubren lo anterior

`commonTest` corre también en el simulador iOS (`./gradlew :shared:iosSimulatorArm64Test`), así que estos tests se
ejecutan en iOS sin cambios: `FastPlateOcrTest` (parser YAML, decodificación, rechazo de configs), `PostprocessingTest`
(recorte 2:1, márgenes, clamp), `PreprocessingTest`, `OcrTest`.

### 2.4 Estado de git al escribir esto

Los cambios de las dos peticiones están **sin commit** en el repo de Windows. Antes de empezar, comprobar en el Mac
que existen (`git log`, `git status`) y que `shared/src/androidMain/assets/ocr/plate_config.yaml` está presente. Si no
están, pedírselos al usuario: no reconstruirlos a partir de este documento.

## 3. Lo que falta en iOS

Hoy `iosMain` solo tiene `MainViewController.kt`, `Platform.ios.kt` y un `ScannerRoute.ios.kt` que muestra
*"The plate scanner is not available on iOS yet."* El build sí está preparado: `shared/build.gradle.kts` descarga
TensorFlowLiteC 2.17.0 (tarea `downloadTensorFlowLiteC`, solo en Mac) y configura la cinterop
(`src/nativeInterop/cinterop/TensorFlowLiteC.def`, paquete `tensorflow.lite.c`); `iosApp/Configuration/Config.xcconfig`
tiene las rutas por SDK y `-framework TensorFlowLiteC -lc++`; `Info.plist` ya tiene `NSCameraUsageDescription`.

Hacer las tareas en este orden. Las tareas 1 a 4 dejan el pipeline funcionando y probado sin cámara; la 5 es la cámara.

### Tarea 1: `IosTfliteModel` (implementación de `TfliteModel`)

Archivo nuevo: `shared/src/iosMain/kotlin/botix/dev/detectorlicenseplateocr/pipeline/IosTfliteModel.kt`.

Interfaz a implementar (`commonMain/.../pipeline/TfliteModel.kt`):

```kotlin
interface TfliteModel : AutoCloseable {
    val signatures: List<String>
    fun inputNames(signature: String): List<String>
    fun outputNames(signature: String): List<String>
    fun inputShape(signature: String, name: String): IntArray
    fun outputShape(signature: String, name: String): IntArray
    /** Inputs: FloatArray or IntArray with the whole tensor, flattened. Outputs: written into the FloatArrays. */
    fun run(signature: String, inputs: Map<String, Any>, outputs: Map<String, FloatArray>)
}
```

El pipeline trabaja **por firmas y nombres**, no por índices (a diferencia del ejemplo de `Baseline.md` 4.5, que usa
`TfLiteInterpreterGetInputTensor(interpreter, 0)`). Usar la API de *signature runner* de la C API:

- `TfLiteInterpreterGetSignatureCount` / `TfLiteInterpreterGetSignatureKey` → `signatures`.
- `TfLiteInterpreterGetSignatureRunner(interpreter, key)` → un runner por firma; crearlo una vez y reutilizarlo.
- `TfLiteSignatureRunnerGetInputCount` / `GetInputName`, `GetOutputCount` / `GetOutputName` → nombres.
- `TfLiteSignatureRunnerGetInputTensor(runner, name)` / `GetOutputTensor(runner, name)` + `TfLiteTensorNumDims` /
  `TfLiteTensorDim` → formas.
- `TfLiteSignatureRunnerAllocateTensors(runner)` una vez, antes del primer `Invoke`.
- Por llamada: `TfLiteTensorCopyFromBuffer` con `usePinned`, `TfLiteSignatureRunnerInvoke`, `TfLiteTensorCopyToBuffer`.
- `close()`: `TfLiteSignatureRunnerDelete` de cada runner, luego interpreter, options y model (ese orden).

**Comprobar primero** que esas funciones están declaradas en
`iosApp/Frameworks/TensorFlowLiteC.xcframework/ios-arm64/TensorFlowLiteC.framework/Headers/c_api.h` (aparece tras
`./gradlew :shared:downloadTensorFlowLiteC`). Si alguna solo está en `c_api_experimental.h`, añadir esa cabecera a
`headers =` del `.def`. No sustituir por la API por índices: `PlateDetector` distingue sus salidas por forma y
`FastPlateOcr` busca la entrada por nombre.

Detalles:

- Entradas: `FloatArray` → tensor float32; `IntArray` → tensor int32 (lo usa el decoder de TrOCR; fast-plate-ocr y el
  detector solo usan float). Cualquier otro tipo: `IllegalArgumentException`, como en Android.
- Validar que el número de bytes coincide con `TfLiteTensorByteSize` antes de copiar; un error claro aquí ahorra crashes.
- Cada estado distinto de `kTfLiteOk` → excepción con el nombre del fichero y la llamada.
- `@OptIn(ExperimentalForeignApi::class)` en el fichero; los tipos llegan como `cnames.structs.*` (ver `Baseline.md` 4.5).
- Constructor/factoría: `fromFile(path: String, threads: Int)`. Hilos: misma regla que Android
  (`defaultThreadCount()`: núcleos disponibles limitados a 1-4; en iOS `NSProcessInfo.processInfo.activeProcessorCount`).
- No es thread-safe, igual que en Android: quien lo use serializa las llamadas.

Referencia de comportamiento: `shared/src/androidMain/.../pipeline/AndroidTfliteModel.kt`.

### Tarea 2: meter los modelos en el bundle

No existe todavía una fase *Copy Model Files* en `iosApp.xcodeproj` (el patrón está en `Baseline.md` 4.4). Añadir una
fase de script que se ejecute **antes de "Sources"** y copie, **conservando las subcarpetas** (las rutas de
`ModelFiles` son `detector/...` y `ocr/...`), solo estos cuatro ficheros:

```sh
set -eu
SRC="$SRCROOT/../shared/src/androidMain/assets"
DEST="$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH"
mkdir -p "$DEST/detector" "$DEST/ocr"
cp "$SRC/detector/rf_detr_license_plates_fp16.tflite" "$SRC/detector/preprocessor_config.json" "$DEST/detector/"
cp "$SRC/ocr/plate_ocr.tflite" "$SRC/ocr/plate_config.yaml" "$DEST/ocr/"
```

**No copiar** el resto de `assets/`: `trocr_placas_int8.tflite` (~394 MB, solo para tests de comparación),
`config_tflite.json`, `vocabulario.json`, `plate_ocr.onnx`, `plate_ocrV1.tflite`, ni
`rf_detr_license_plates_int8.tflite`. Leer el nombre real del detector de `ModelFiles.DETECTOR_MODEL` por si cambia.

Lectura en Kotlin, con subcarpeta:

```kotlin
fun bundledPath(asset: String): String {
    val dir = asset.substringBeforeLast('/', "")
    val file = asset.substringAfterLast('/')
    return NSBundle.mainBundle.pathForResource(file.substringBeforeLast('.'), file.substringAfterLast('.'), dir)
        ?: error("Model file '$asset' is not in the app bundle")
}
```

Si `inDirectory` da problemas, alternativa válida: copiar a la raíz del bundle y resolver por nombre de fichero, pero
entonces dejarlo explicado junto a `bundledPath`.

### Tarea 3: `createLicensePlatePipeline` para iOS

Archivo nuevo en `iosMain/.../pipeline/` (p. ej. `IosPipelineFactory.kt`), equivalente a la función del mismo nombre
de `androidMain/.../pipeline/AndroidTfliteModel.kt`:

```kotlin
fun createLicensePlatePipeline(
    threads: Int = defaultThreadCount(),
    params: PipelineParams = PipelineParams(),   // cropAspectRatio = 2.0 por defecto: no tocar
): LicensePlatePipeline
```

- Lee `DetectorConfig.parse(...)` de `ModelFiles.DETECTOR_CONFIG` y `FastOcrConfig.parse(...)` de
  `ModelFiles.FAST_OCR_CONFIG` (texto UTF-8 del bundle, p. ej. `NSString.stringWithContentsOfFile`).
- Crea `IosTfliteModel` para `ModelFiles.DETECTOR_MODEL` y `ModelFiles.FAST_OCR_MODEL`, luego `FastPlateOcr(model, config)`
  y `LicensePlatePipeline(detector, detectorConfig, ocr, params)`.
- Si algo falla a mitad, cerrar lo ya abierto (mismo `try/catch` que en Android).
- Solo fast-plate-ocr: TrOCR no va en la app iOS.
- `defaultThreadCount()` es hoy una función de `androidMain`. Para iOS, crear la suya en `iosMain` (o moverla a un
  `expect`/`actual` si queda más limpio; si se mueve, no cambiar su comportamiento en Android).

### Tarea 4: test de integración en el simulador

Objetivo: demostrar en iOS que el modelo nuevo, el YAML y el recorte 2:1 funcionan juntos, sin cámara.

- El binario de test del simulador no tiene bundle. Pasar la carpeta de assets como en `Baseline.md` 4.4:
  `tasks.withType<KotlinNativeSimulatorTest>().configureEach { environment("SIMCTL_CHILD_SCANNER_ASSETS_DIR", project.file("src/androidMain/assets").absolutePath) }`
  y leer `SCANNER_ASSETS_DIR` en el test (hacer que la factoría de la tarea 3 acepte una función `path(asset)` para
  poder usar esa carpeta en lugar del bundle).
- Test mínimo (`shared/src/iosTest/.../pipeline/`):
  1. `FastOcrConfig.parse` del `plate_config.yaml` real da 64, 128, 10 y el alfabeto de 37.
  2. `IosTfliteModel` de `plate_ocr.tflite`: 1 firma, entrada `[1, 64, 128, 3]`, salida `[1, 10, 37]`.
  3. `FastPlateOcr` sobre un `RgbImage` cualquiera devuelve `confidence` en `0..1` y `tokenIds.size == 10`.
  4. Pipeline completo creado sin errores (detector + OCR).
- Opcional pero recomendable: las 4 imágenes con verdad conocida de
  `shared/src/androidDeviceTest/kotlin/.../pipeline/FastPlateOcrReferenceTest.kt` (`GROUND_TRUTH`, imágenes en
  `shared/src/androidDeviceTest/assets/reference/`). Decodificarlas con ImageIO (`CGImageSourceCreateWithURL` +
  dibujar en un `CGBitmapContext` **al mismo tamaño**, sin escalar) y comparar con lo que da Android en el mismo
  commit. No usar CoreImage en tests (falla en el simulador, `Baseline.md` 5).

### Tarea 5: cámara (`ScannerRoute.ios.kt`)

Replicar el flujo de Android con AVFoundation. Referencias en `androidMain/.../scanner/`: `ScannerViewModel.kt`
(estado, hilo de análisis, publicación de resultados, historial), `FrameCropper.kt` (recorte de la guía) y
`ScannerRoute.android.kt` (permiso + `ScannerScreen`).

- UI compartida, sin cambios: `ScannerScreen(state, actions, preview)` y `CameraPermissionScreen(...)` de
  `commonMain/.../scanner/`. iOS solo aporta `preview` (un `UIKitView` con `AVCaptureVideoPreviewLayer`,
  `videoGravity = resizeAspectFill`, que debe ocupar el `Modifier` recibido) y una implementación de `ScannerActions`.
- Captura: `AVCaptureVideoDataOutput` con `kCVPixelFormatType_32BGRA`, `alwaysDiscardsLateVideoFrames = true`, una
  cola serie que también carga el pipeline y ejecuta toda la inferencia (equivale al `KEEP_ONLY_LATEST` + hilo único
  de Android). Resolución alta, del orden de 1920×1440 / 4:3, para que una placa dentro de la guía supere de sobra los
  60 px de ancho que exige el OCR.
- Recorte de la guía: `ScanRegion.inView(viewWidth, viewHeight)` da la guía como fracción de la vista. Mapear
  vista → frame visible (aspect fill: escalar para cubrir y centrar) → buffer, rotar **una sola vez**, y entregar al
  pipeline solo esa región a resolución completa, como `FrameCropper`. `pipeline.run(roi, maxPlatesToRead = 1)`.
- Píxeles: `RgbImage.pixels` es `IntArray` con `0xRRGGBB` (sin alfa). El buffer es BGRA: **intercambiar B y R**.
  El redimensionado lo hace el código común (`toNhwc` / `toNchw` con bilineal propio); no escalar con
  `CGContextDrawImage` (`Baseline.md` 5).
- Cámara frontal: el preview está espejado y el frame no; espejar las cajas para el overlay
  (`NormalizedRect.mirroredHorizontally()`), igual que Android.
- Permiso: `AVCaptureDevice.authorizationStatusForMediaType` / `requestAccessForMediaType`; si está denegado,
  `CameraPermissionScreen(permanentlyDenied = true)` con botón que abra `UIApplicationOpenSettingsURLString`.
- `ScannerActions`: zoom con `videoZoomFactor` (entre `minAvailableVideoZoomFactor` y `maxAvailableVideoZoomFactor`),
  linterna con `torchMode`, cambio de cámara, historial. Pantalla siempre encendida mientras escanea
  (`UIApplication.sharedApplication.idleTimerDisabled`).
- Liberar el pipeline en la misma cola de análisis al salir, para no cerrarlo durante una inferencia.

**El simulador no tiene cámara**: esta tarea solo se puede probar en un iPhone real. Si no hay uno disponible,
dejar la tarea 5 compilando y decir claramente que no se ha probado.

## 4. Trampas conocidas

- **BGRA → RGB**: si se olvida el intercambio, el OCR lee mal con confianza alta, no con confianza baja.
- **Doble rotación**: los buffers de iOS llegan sin rotar; no poner `videoRotationAngle` en la conexión de salida y
  rotar solo en el recorte.
- **No dividir entre 255**: los dos modelos reciben 0-255 (fast-plate-ocr) o la normalización que diga
  `preprocessor_config.json` (detector); eso ya lo hace el código común. No añadir normalización en iOS.
- **No cambiar `PipelineParams()`** en la app: el recorte 2:1 (petición 2) depende de su valor por defecto.
- **Nombres de tensores**: no codificar `input_layer_2` ni `serving_default`; cambian entre exportaciones.
- **Memoria**: no cargar TrOCR en iOS (394 MB).

## 5. Verificación y criterios de terminado

Comandos (desde la raíz del repo, en el Mac):

```bash
./gradlew :shared:downloadTensorFlowLiteC
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
./gradlew :shared:testAndroidHostTest
```

Después, abrir `iosApp/iosApp.xcodeproj` y ejecutar en el simulador (tareas 1-4) y en un iPhone (tarea 5).

Terminado cuando:

- [ ] `IosTfliteModel` implementa `TfliteModel` con la API de firmas y pasa el test de la tarea 4.
- [ ] El bundle de la app contiene exactamente los 4 ficheros de la tarea 2 (comprobar en el `.app` generado).
- [ ] `createLicensePlatePipeline` de iOS carga detector + fast-plate-ocr con `PipelineParams()` por defecto.
- [ ] `./gradlew :shared:iosSimulatorArm64Test` pasa, incluidos `FastPlateOcrTest` y `PostprocessingTest`.
- [ ] Los tests de host de Android siguen igual que antes (ver nota abajo).
- [ ] Tarea 5: escanea una placa real en un iPhone, o queda dicho explícitamente que no se ha podido probar.
- [ ] `Pipeline.md` y `Traceability.md` actualizados con lo que se haya añadido (fase de copia, ficheros nuevos).

Nota: al escribir esto, 2 tests de host de Android ya fallaban **por un motivo ajeno a este trabajo**:
`ModelAssetsTest.detectorConfigMatchesTheGuide` (espera 576, el detector actual es de 384) y
`bundledFilesMatchTheExportedHashes` (hash del detector antiguo). Vienen del commit `9114066`, que cambió el detector
sin actualizar el test. Si siguen fallando, no forman parte de esta tarea; si alguien ya los arregló, deben pasar.
