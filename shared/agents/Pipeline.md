# Lectura de placas con TFLite en una app Kotlin Multiplatform

Guía de integración para implementar, en una app KMP (Android e iOS), el pipeline de lectura de matrículas con los dos
modelos TFLite (LiteRT) exportados en este proyecto:

1. **Detector de placas** (RF-DETR): encuentra las matrículas en una foto.
2. **OCR** (TrOCR): lee el texto de cada matrícula recortada.

Todo lo que hay que implementar está aquí: qué ficheros empaquetar, qué tensores entran y salen de cada modelo, el
preprocesado y el postprocesado exactos, el bucle de lectura del OCR, el formato del resultado y las pruebas con las
que comprobar que la implementación da lo mismo que la de referencia. No se prescribe ninguna librería: basta un
runtime de TFLite/LiteRT que ejecute **firmas** (*signature runners*) y un decodificador de imágenes a píxeles RGB.

La implementación de referencia está en `notebooks/Pipeline_TFLite.ipynb` de este repositorio (Python + NumPy, sin
PyTorch). Si algo de esta guía resultara ambiguo, ese cuaderno es la fuente de verdad.

---

## 1. Visión general

```
Foto (JPEG/PNG/WebP/HEIC…)
  │ decodificar a RGB, aplicar orientación EXIF
  ▼
Imagen original W×H (RGB)
  │ redimensionar a 576×576 (sin mantener proporción), normalizar, NCHW
  ▼
DETECTOR  ──►  300 candidatos (logit + caja cx,cy,w,h normalizada)
  │ sigmoide, umbral 0.4, cajas a píxeles de la imagen original
  ▼
Placas detectadas [x0,y0,x1,y1] + confianza
  │ para cada placa: recorte con margen (8 % ancho, 15 % alto)
  │ si el recorte mide < 60 px de ancho → no se lee
  ▼
Recorte de la placa
  │ redimensionar a alto×ancho del OCR (hoy 128×128), normalizar, NCHW
  ▼
OCR firma "encoder"  ──►  características [1, N, 768]
  │ bucle (máx. 15 pasos): OCR firma "decoder" con 16 tokens → siguiente token = argmax
  ▼
Tokens  ──►  texto con vocabulario.json  ──►  normalización (A-Z, 0-9)
  ▼
Resultado: lista de placas con caja, confianza de detección y texto
```

Los dos modelos son independientes: el detector no necesita el OCR y viceversa (el OCR también puede leer una imagen
que ya sea el recorte de una placa).

---

## 2. Ficheros que debe contener la app

### 2.1 Lista

| Fichero (nombre original) | Origen en este repositorio | Tamaño | Obligatorio | Uso |
|---|---|---|---|---|
| `rf_detr_license_plates.tflite` | `outputs/rf_detr_license_plates/` | 124 677 256 B (~125 MB) | Sí | Modelo detector |
| `preprocessor_config.json` | `outputs/rf_detr_license_plates/final/` | 442 B | Recomendado | Preprocesado del detector (tamaño, media, desviación) |
| `trocr_placas_int8.tflite` | `outputs/trocr_placas_rellenas_colombia/tflite/` | 394 347 488 B (~394 MB) | No (solo tests) | OCR TrOCR (pesos int8): ya no va en la app; lo usan las pruebas de comparación en dispositivo |
| `config_tflite.json` | `outputs/trocr_placas_rellenas_colombia/tflite/` | 477 B | Con TrOCR | Preprocesado de TrOCR y tokens especiales |
| `vocabulario.json` | `outputs/trocr_placas_rellenas_colombia/tflite/` | 964 188 B | Con TrOCR | Id de token → texto |
| `trocr_placas.tflite` | `outputs/trocr_placas_rellenas_colombia/tflite/` | 1 540 603 800 B (~1,5 GB) | **No** | OCR float32: solo para pruebas en escritorio; no debe ir en la app |
| `plate_ocr.tflite` | fine-tuning de fast-plate-ocr | 1 597 848 B (~1,6 MB) | Sí | Modelo OCR de la app (`OcrEngine.FAST_PLATE_OCR`, el predeterminado): entrada NHWC `[1, 64, 128, 3]` float32 con píxeles RGB 0-255 (el `1/255` está dentro del modelo), salida `[1, 10, 37]` ya con softmax |
| `plate_ocr_config.json` | escrito a mano con los valores del `plate_config.yaml` del fine-tuning | 201 B | Sí | Tamaño de entrada, número de posiciones, alfabeto y carácter de relleno |

SHA-256 de los ficheros exportados a fecha de esta guía (para verificar descargas y empaquetado):

```
f4c2675905e42082630ec7b657ac99881741b0c2d4a9b6aabbc61fe26a6d30ab  rf_detr_license_plates.tflite
4e55a721a4ca13007d7ee4bbe914958060519f64da7c6e21b8e75732d46b8fb6  preprocessor_config.json
da7499dbdc1855c8bdf48547cfc79912db9cd7db592dae9dfca52aa62403bc6e  trocr_placas_int8.tflite
868f79f992a3f1591ecad72550826bf2660d2417bd266e11ef34da1ecacef60e  config_tflite.json
e587ea3e69db8e5d3f2155dea57e50a64d58b146f358c9a06dc19f160fbd272d  vocabulario.json
ac6a129b8f2a5ba379f8b126c96fcd7c69428fdb8c2194101404a324eed72aaf  plate_ocr.tflite
```

Si se reentrena o se vuelve a exportar un modelo, estos valores cambian: hay que actualizar la tabla (ver sección 12).

### 2.2 Estructura recomendada

Mantener los nombres originales facilita saber de dónde sale cada fichero:

```
models/
├── detector/
│   ├── rf_detr_license_plates.tflite
│   └── preprocessor_config.json
└── ocr/
    ├── trocr_placas_int8.tflite
    ├── config_tflite.json
    └── vocabulario.json
```

### 2.3 Entrega de los modelos

Los dos `.tflite` suman ~520 MB. Es más que lo que conviene meter en el paquete de instalación de una app móvil, así
que se recomienda:

* descargarlos en el primer uso (o con el mecanismo de recursos bajo demanda de cada plataforma) a almacenamiento
  privado de la app;
* verificar el SHA-256 tras la descarga y antes de cargarlos, y volver a descargar si no coincide;
* no cargarlos desde un fichero incompleto: descargar a un nombre temporal y renombrar al terminar.

Los tres JSON son pequeños y pueden ir dentro del paquete o descargarse con los modelos, pero **siempre deben
corresponder a la misma exportación que los `.tflite`** (en especial `config_tflite.json` y `vocabulario.json` con
`trocr_placas_int8.tflite`).

### 2.4 Contenido de los JSON

`config_tflite.json` (OCR):

```json
{
  "entrada": {
    "alto": 128,
    "ancho": 128,
    "formato": "NCHW float32 RGB",
    "reescalado": 0.00392156862745098,
    "media": [0.5, 0.5, 0.5],
    "desviacion": [0.5, 0.5, 0.5]
  },
  "tokens": { "start": 0, "pad": 1, "eos": 2, "max_length": 16 },
  "firmas": {
    "encoder": "args_0=pixel_values -> estados",
    "decoder": "args_0=input_ids int32 [1, max_length], args_1=estados -> logits"
  }
}
```

`alto` y `ancho` dependen de cómo se entrenó el modelo (`IMAGE_SIZE` en `TrOCR_Placas.ipynb`): **se leen del JSON en
tiempo de ejecución, nunca se fijan en el código.** Lo mismo con los tokens.

`vocabulario.json`: objeto con 50 265 entradas `"id": "texto"` (las claves son cadenas con el número del token).
Ejemplos: `"0": "<s>"`, `"1": "<pad>"`, `"2": "</s>"`, `"3": "<unk>"`, `"250": "A"`, `"1301": "Z"`, `"288": "0"`,
`"466": "9"`, `"4546": "AB"`, `"39819": "597"`, `"83": " A"`, `"3226": "*"`. Un token puede contener varios caracteres
y algunos empiezan por un espacio.

`preprocessor_config.json` (detector): los campos que se usan son `size.height` (576), `size.width` (576),
`rescale_factor` (1/255), `image_mean` (`[0.485, 0.456, 0.406]`) e `image_std` (`[0.229, 0.224, 0.225]`). El resto de
campos se ignoran.

---

## 3. Requisitos del runtime

* Un intérprete de TFLite/LiteRT reciente que permita ejecutar **por nombre de firma** (el OCR tiene dos firmas en un
  mismo fichero; sin la API de firmas no se puede usar).
* Tensores `float32` e `int32`. El OCR int8 tiene los pesos cuantizados, pero sus entradas, salidas y cálculos son
  `float32`: no hay que cuantizar nada en la app.
* Ejecución en **CPU** (con varios hilos). Es la configuración validada. Los delegados de GPU/NPU no se han probado;
  si se usan, hay que pasar las pruebas de la sección 10 con ellos.
* Un intérprete por modelo, creado una sola vez y reutilizado. Un intérprete **no** es seguro para usarse desde varios
  hilos a la vez: serializar las llamadas a cada modelo.

Al cargar cada modelo, comprobar las firmas y formas de la sección 4 y 6 y fallar con un error claro si no coinciden
(p. ej. porque se empaquetó un `config_tflite.json` de otra exportación).

---

## 4. Modelo 1: detector de placas

### 4.1 Contrato

Fichero `rf_detr_license_plates.tflite`, una firma: `serving_default`.

| | Nombre | Tipo | Forma | Contenido |
|---|---|---|---|---|
| Entrada | `args_0` | float32 | `[1, 3, 576, 576]` | Imagen preprocesada, NCHW |
| Salida | `output_0` | float32 | `[1, 300, 1]` | Logit de "placa" de cada uno de los 300 candidatos |
| Salida | `output_1` | float32 | `[1, 300, 4]` | Caja de cada candidato: `cx, cy, w, h` normalizados a 0–1 |

Si el runtime no expone los nombres de salida, distinguirlas por la forma (última dimensión 1 = logits, 4 = cajas).

### 4.2 Preprocesado

A partir de la imagen original (ya con la orientación EXIF aplicada y en RGB):

1. **Redimensionar a 576×576 píxeles sin mantener la proporción** (se estira; no hay *letterbox* ni relleno).
   Interpolación bilineal.
2. Para cada píxel y canal `c` (R, G, B, en ese orden), con el valor entero `v` de 0 a 255:
   `x = (v × 0.00392156862745098 − mean[c]) / std[c]`, con `mean = [0.485, 0.456, 0.406]` y
   `std = [0.229, 0.224, 0.225]`.
3. Colocar en formato **NCHW**: índice plano `c × 576 × 576 + y × 576 + x` (primero todo el plano R, luego G, luego B).
   Buffer de `3 × 576 × 576 = 995 328` floats (3 981 312 bytes, orden de bytes nativo).

Detalles que cambian el resultado si se hacen mal:

* Orden de canales **RGB**, no BGR. Descartar el canal alfa; las imágenes en escala de grises se replican a 3 canales.
* **Aplicar la orientación EXIF** antes de nada: las cajas se devuelven en las coordenadas de la imagen tal como se ve.
* Las fotos de cámara suelen ser mucho mayores que 576 px. La referencia reduce con un filtro bilineal que promedia
  (antialias). Si el redimensionado de la plataforma no promedia al reducir tanto, se puede reducir primero en pasos
  a la mitad y después a 576×576. Diferencias pequeñas aquí mueven las confianzas en centésimas, no más.

### 4.3 Inferencia

Ejecutar la firma `serving_default` con `args_0` = buffer del paso anterior. Leer `output_0` y `output_1`.

### 4.4 Postprocesado

Para cada candidato `i` de 0 a 299:

1. `score_i = 1 / (1 + exp(−logit_i))` (sigmoide de `output_0[0, i, 0]`).
2. Caja normalizada `cx, cy, w, h = output_1[0, i, 0..3]` → esquinas normalizadas
   `x0 = cx − w/2`, `y0 = cy − h/2`, `x1 = cx + w/2`, `y1 = cy + h/2`.
3. Pasar a píxeles de la **imagen original** multiplicando las `x` por su ancho `W` y las `y` por su alto `H`. (Como el
   redimensionado estiró la imagen, las coordenadas normalizadas equivalen directamente a proporciones de la original.)
4. Quedarse con los candidatos con `score > 0.4` (umbral `DET_THRESHOLD` de referencia) y ordenarlos por `score`
   descendente.
5. Recortar las esquinas al rango `[0, W]` y `[0, H]` al usarlas (pueden salirse ligeramente).

No se aplica supresión de no máximos: los modelos tipo DETR están entrenados para no repetir detecciones y la
referencia no la usa. Para reproducir la referencia, no añadirla.

Salida del detector: lista de `{ box: [x0, y0, x1, y1] en píxeles (float), score: float }`.

### 4.5 Recorte de cada placa

Para cada caja `[x0, y0, x1, y1]`:

1. `padX = (x1 − x0) × 0.08` y `padY = (y1 − y0) × 0.15`.
2. Rectángulo de recorte:
   `left = max(0, floor(x0 − padX))`, `top = max(0, floor(y0 − padY))`,
   `right = min(W, ceil(x1 + padX))`, `bottom = min(H, ceil(y1 + padY))`.
3. Recortar ese rectángulo de la **imagen original a resolución completa** (no de la imagen de 576×576).
4. Si el recorte mide **menos de 60 px de ancho** (`MIN_OCR_WIDTH`), no se lee: a esa resolución el OCR devuelve ruido.
   La placa se sigue devolviendo (con su caja y confianza) marcada como no leída.

Si la imagen original se reduce al decodificarla para ahorrar memoria, hacerlo de forma moderada (lado mayor de al
menos ~2000 px): los recortes deben conservar la mayor resolución posible.

---

## 5. Resultado del detector con recorte

Para cada placa detectada quedan: la caja en píxeles, la confianza de detección, el recorte (imagen RGB) y si es
legible (`ancho del recorte ≥ 60`). Solo los recortes legibles pasan al OCR.

---

## 6. Modelo 2: OCR

### 6.1 Contrato

Fichero `trocr_placas_int8.tflite`, dos firmas: `encoder` y `decoder`.

**Firma `encoder`**

| | Nombre | Tipo | Forma (modelo actual) | Contenido |
|---|---|---|---|---|
| Entrada | `args_0` | float32 | `[1, 3, alto, ancho]` = `[1, 3, 128, 128]` | Recorte preprocesado, NCHW |
| Salida | `output_0` | float32 | `[1, N, 768]` = `[1, 65, 768]` | Características de la imagen |

`N = (alto / 16) × (ancho / 16) + 1`. Leer la forma real del modelo en vez de calcularla.

**Firma `decoder`**

| | Nombre | Tipo | Forma | Contenido |
|---|---|---|---|---|
| Entrada | `args_0` | int32 | `[1, 16]` | Tokens generados hasta ahora, rellenados con `pad` |
| Entrada | `args_1` | float32 | `[1, N, 768]` | Salida del `encoder`, sin modificar |
| Salida | `output_0` | float32 | `[1, 16, 50265]` | Logits del siguiente token para cada posición |

`16` es `tokens.max_length` y `50265` el tamaño del vocabulario (igual al número de entradas de `vocabulario.json`).

### 6.2 Preprocesado del recorte

Igual que el del detector, con los valores de `config_tflite.json`:

1. Redimensionar el recorte a `ancho × alto` (hoy 128×128) **sin mantener la proporción**, bilineal.
2. `x = (v × reescalado − media[c]) / desviacion[c]` → con los valores actuales, `x = v / 127.5 − 1` (rango −1 a 1).
3. NCHW, índice plano `c × alto × ancho + y × ancho + x`, canales RGB.

### 6.3 Lectura (búsqueda voraz)

El OCR genera el texto token a token. El `decoder` recibe siempre 16 tokens; las posiciones que aún no se han generado
van con `pad` y no afectan a las anteriores (el decodificador es causal), así que no hace falta ningún estado ni caché.

```kotlin
// Pseudocódigo en Kotlin común. `runEncoder`/`runDecoder` son la llamada al runtime por nombre de firma.
fun readPlate(crop: RgbImage): List<Int> {
    val pixels: FloatArray = preprocess(crop, cfg.alto, cfg.ancho, cfg.reescalado, cfg.media, cfg.desviacion)
    val features: FloatArray = runEncoder(pixels)            // [1, N, 768], se calcula una sola vez

    val maxLen = cfg.tokens.maxLength                         // 16
    val ids = IntArray(maxLen) { cfg.tokens.pad }             // [pad, pad, …]
    ids[0] = cfg.tokens.start                                 // [start, pad, pad, …]
    val generated = mutableListOf<Int>()

    for (t in 0 until maxLen - 1) {                          // t = 0 … 14
        val logits: FloatArray = runDecoder(ids, features)    // [1, 16, V] aplanado
        val next = argmax(logits, from = t * vocabSize, until = (t + 1) * vocabSize) - t * vocabSize
        if (next == cfg.tokens.eos) break
        ids[t + 1] = next
        generated += next
    }
    return generated                                          // sin start, sin eos, sin pad
}
```

* En el paso `t` solo interesa la fila `t` de los logits: `output_0[0, t, 0..V-1]`, es decir, los valores
  `t × V … (t + 1) × V − 1` del buffer plano.
* El bucle termina al generar `eos` (2) o tras 15 pasos. Una placa típica termina en 4–8 pasos.
* `argmax` estricto: con empates, el primer índice.

### 6.4 De tokens a texto

1. Para cada id generado, buscar su texto en `vocabulario.json` y concatenar en orden. Ignorar `start`, `pad` y `eos`
   si aparecieran.
2. `textoOcr = concatenación.trim()`.
3. Texto de placa normalizado (el que mostrar o comparar): pasar a mayúsculas y quitar todo lo que no sea `A-Z`,
   `0-9` o `-` (la referencia, en `Pipeline_Placas_OCR.ipynb`, también conserva `*`; con el modelo actual no aparece).
   Guardar también `textoOcr` sin normalizar para depuración.

Ejemplo de decodificación: los ids `[39819, 3226, 574, 530, 3226]` dan `"597" + "*" + "L" + "K" + "*"` = `597*LK*`.

### 6.5 Confianza del OCR (opcional, no validada)

La referencia no calcula una confianza de lectura. Si la app la necesita, una opción es, en cada paso, aplicar
`softmax` a la fila de logits y quedarse con la probabilidad del token elegido; la confianza de la placa sería el
mínimo (o el producto) de esas probabilidades. No se ha calibrado: usarla solo como indicación relativa.

---

## 7. Pipeline completo, paso a paso

Para cada foto:

1. **Leer la imagen**: decodificar a RGB de 8 bits, aplicar la orientación EXIF, descartar el alfa. Guardar `W` y `H`.
2. **Preprocesar para el detector** (4.2) → buffer `[1, 3, 576, 576]`.
3. **Detectar** (4.3) → `output_0`, `output_1`.
4. **Postprocesar** (4.4): sigmoide, esquinas en píxeles, umbral 0.4, orden por confianza.
5. Para cada placa, **recortar** con margen (4.5) y decidir si es legible (ancho ≥ 60 px).
6. Para cada recorte legible:
    1. **Preprocesar para el OCR** (6.2) → `[1, 3, alto, ancho]`.
    2. **`encoder`** → características.
    3. **Bucle `decoder`** (6.3) → tokens.
    4. **Tokens a texto** y normalización (6.4).
7. **Devolver el resultado** (sección 8).

Parámetros de referencia, que conviene tener en un único sitio configurable:

| Parámetro | Valor | Dónde se usa |
|---|---|---|
| `DET_THRESHOLD` | 0.4 | Confianza mínima de una placa (4.4) |
| `CROP_PADDING` | 0.08 (horizontal), 0.15 (vertical) | Margen del recorte (4.5) |
| `MIN_OCR_WIDTH` | 60 px | Ancho mínimo del recorte para leerlo (4.5) |
| Tamaño detector | 576×576 | `preprocessor_config.json` |
| Tamaño OCR | 128×128 (hoy) | `config_tflite.json` |
| `max_length` | 16 | `config_tflite.json` |

---

## 8. Formato del resultado

Estructura sugerida (Kotlin común):

```kotlin
data class PlateReading(
    val box: FloatArray,          // [x0, y0, x1, y1] en píxeles de la imagen original (tras EXIF)
    val detectionScore: Float,    // 0–1, sigmoide del logit del detector
    val cropWidth: Int,           // ancho del recorte en píxeles
    val cropHeight: Int,
    val readable: Boolean,        // cropWidth >= MIN_OCR_WIDTH
    val text: String?,            // texto normalizado (A-Z, 0-9, -); null si no se leyó
    val rawText: String?,         // texto del OCR sin normalizar
    val tokenIds: List<Int>?,     // tokens generados, útil para depurar
)

data class PipelineResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val plates: List<PlateReading>,   // ordenadas por detectionScore descendente
    val detectorMillis: Long,
    val ocrMillis: Long,
)
```

Casos que deben estar contemplados:

* **Ninguna placa**: `plates` vacía (no es un error).
* **Placa no legible** por tamaño: `readable = false`, `text = null`.
* **OCR vacío**: si el primer token generado es `eos`, `text = ""`. Tratarlo como "no se pudo leer".
* **Varias placas** en la misma foto: se leen todas; el orden es el de confianza de detección.

---

## 9. Arquitectura en KMP

Reparto recomendado entre código común y código de plataforma:

**Común (`commonMain`)**, sin dependencias de plataforma:

* Lectura de los JSON y validación de su contenido.
* Preprocesado: de un array de píxeles RGB (`width`, `height`, `IntArray`/`ByteArray`) a `FloatArray` NCHW
  normalizado, incluido el redimensionado bilineal si se implementa a mano.
* Postprocesado del detector, recorte con margen, bucle del OCR, decodificación del vocabulario y normalización.
* Orquestación del pipeline y el modelo de datos del resultado.

**Plataforma (`expect`/`actual`)**, solo lo imprescindible:

* Decodificar la imagen a píxeles RGB y leer/aplicar la orientación EXIF.
* Cargar un `.tflite` desde un fichero y ejecutar una firma por nombre con buffers `float32`/`int32`.
* Ubicación de los ficheros descargados y verificación del SHA-256 (o hacerlo en común si hay una implementación
  disponible).

Una interfaz mínima para el runtime:

```kotlin
interface TfliteModel : AutoCloseable {
    fun signatures(): List<String>
    fun inputShape(signature: String, name: String): IntArray
    fun outputShape(signature: String, name: String): IntArray
    // Ejecuta la firma; las entradas son FloatArray o IntArray según el tensor.
    fun run(signature: String, inputs: Map<String, Any>): Map<String, FloatArray>
}
```

Otras recomendaciones:

* Cargar los dos modelos una vez (tarda poco; el OCR ocupa ~400 MB de fichero, que el runtime suele mapear en memoria)
  y reutilizarlos. Cerrarlos al salir de la funcionalidad para liberar memoria.
* Ejecutar el pipeline fuera del hilo de interfaz. Serializar las llamadas a cada intérprete.
* Reutilizar los buffers de entrada y salida entre llamadas: el `decoder` devuelve `16 × 50265` floats (~3,2 MB) en
  cada paso.
* Configurar varios hilos de CPU en el intérprete (p. ej. el número de núcleos grandes del dispositivo).

---

## 10. Pruebas de validación

La implementación debe reproducir la de referencia. Pruebas en orden, de la más simple a la completa.

### 10.1 Preprocesado (unitarias)

Imagen de 1×1 px, sin redimensionar (o una imagen uniforme de cualquier tamaño), valores esperados por canal:

| Píxel RGB | Detector R, G, B | OCR R, G, B |
|---|---|---|
| (255, 0, 0) | 2.248908, −2.035714, −1.804444 | 1.0, −1.0, −1.0 |
| (0, 0, 0) | −2.117904, −2.035714, −1.804444 | −1.0, −1.0, −1.0 |
| (128, 128, 128) | 0.074065, 0.205182, 0.426492 | 0.003922, 0.003922, 0.003922 |

Y comprobar el orden NCHW: en una imagen 2×1 con el píxel izquierdo rojo y el derecho azul, el buffer del OCR es
`[1, −1, −1, −1, −1, 1]` (plano R: izq., der.; plano G; plano B).

### 10.2 Postprocesado del detector (unitaria)

Imagen de 1000×500 px, un candidato con `logit = 0` y caja `(cx, cy, w, h) = (0.5, 0.5, 0.2, 0.1)`:
`score = 0.5`, caja en píxeles `[400, 225, 600, 275]`. Con recorte: `padX = 16`, `padY = 7.5` →
rectángulo `[384, 217, 616, 283]` (ancho 232 → legible).

### 10.3 Vocabulario (unitaria)

`[39819, 3226, 574, 530, 3226]` → `597*LK*`; `[250]` → `A`; `[83]` → `" A"` (con espacio; tras `trim`, `A`).

### 10.4 Pipeline completo (integración)

Con las imágenes de `data/vehicles` de este repositorio, la implementación de referencia (int8, umbral 0.4) da:

| Imagen | Confianza | Caja `[x0, y0, x1, y1]` | Recorte | Texto |
|---|---|---|---|---|
| `cars.png` | 0.594 | [140, 313, 160, 322] | 24×13 | (no legible) |
| `gettyimages-1645361278-612x612.jpg` | 0.895 | [230, 184, 360, 225] | 152×55 | `NZZ626` |
| `heavy-traffic-la-six-lanes-footage-000759983_iconl.webp` | 0.682 | [108, 184, 122, 190] | 18×9 | (no legible) |
| ″ | 0.484 | [169, 212, 188, 217] | 23×9 | (no legible) |
| ″ | 0.481 | [41, 170, 51, 175] | 13×8 | (no legible) |
| `highway_101_traffic.webp` | 0.671 | [579, 735, 616, 755] | 44×27 | (no legible) |
| ″ | 0.633 | [141, 583, 185, 602] | 52×26 | (no legible) |
| ″ | 0.604 | [159, 737, 200, 757] | 49×26 | (no legible) |
| ″ | 0.484 | [344, 430, 381, 445] | 44×19 | (no legible) |
| ″ | 0.480 | [1110, 607, 1159, 624] | 58×23 | (no legible) |
| ″ | 0.447 | [23, 473, 64, 491] | 48×24 | (no legible) |
| ″ | 0.429 | [510, 720, 556, 735] | 54×20 | (no legible) |
| ″ | 0.424 | [678, 563, 724, 579] | 54×21 | (no legible) |
| `images (1).jpeg` | 0.897 | [242, 312, 399, 361] | 183×65 | `CUL718` |
| `images (2).jpeg` | 0.828 | [286, 515, 366, 543] | 94×38 | `LJ73` |
| `images.jpeg` | 0.888 | [260, 283, 341, 317] | 95×46 | `GRF474` |
| `istockphoto-523091055-612x612.jpg` | 0.504 | [275, 307, 319, 320] | 52×17 | (no legible) |
| `traffic-166453_1280.jpg` | 0.572 | [222, 1241, 256, 1262] | 40×28 | (no legible) |
| ″ | 0.476 | [161, 951, 182, 978] | 25×36 | (no legible) |
| ″ | 0.432 | [299, 949, 323, 981] | 29×42 | (no legible) |

Tolerancias (por diferencias de redimensionado y decodificación JPEG entre plataformas):

* Número de placas por imagen: **igual**. Si una placa está justo en el umbral (±0.02 de 0.4) puede aparecer o no.
* Confianza: ±0.02. Cajas: ±3 px en cada coordenada.
* Texto: **igual**. Si difiere en un carácter en alguna placa, revisar primero el redimensionado y la normalización del
  OCR (sección 6.2).

Los textos no son las matrículas reales (por ejemplo, `NZZ626` para `NZQ7G26`): son lo que lee el modelo actual, que
es lo que la app debe reproducir. Ver sección 11.

Para regenerar esta tabla (tras reentrenar o volver a exportar), ejecutar `notebooks/Pipeline_TFLite.ipynb`: escribe
`outputs/pipeline_tflite/lecturas_tflite.csv` con las mismas columnas. Conviene copiar esas imágenes y el CSV a los
tests de la app.

### 10.5 Rendimiento de referencia

En la CPU de un ordenador de sobremesa (i7-14700KF, 8 hilos): detector ~0,7 s por imagen y OCR ~0,6–1,5 s por placa
(int8). En un móvil será más lento; medirlo en los dispositivos objetivo. El OCR cuesta un `encoder` más un `decoder`
por token generado, así que su tiempo crece con la longitud del texto.

---

## 11. Limitaciones del modelo actual

* **OCR entrenado con placas españolas y colombianas.** Con matrículas de otros países tiende a forzar su formato
  (6–7 caracteres): en las fotos de `data/vehicles` recorta o cambia caracteres. En las placas colombianas que no vio
  en el entrenamiento acierta la placa completa en el 76,8 % de los casos y el 96,0 % de los caracteres.
* **Entrada del OCR de 128×128.** Una matrícula es ancha; al comprimirla en un cuadrado pierde resolución horizontal.
  Un reentrenamiento con otro tamaño cambiará `alto`/`ancho` en `config_tflite.json`, y la app debe adaptarse sola si
  los lee del JSON.
* **Placas pequeñas.** Por debajo de 60 px de ancho no se leen; en fotos de tráfico lejano la mayoría de placas quedan
  así.
* **Detector** entrenado con una sola clase (`license_plate`); detecta bien placas cercanas (confianzas ~0.9) y más
  bajas (0.4–0.7) en placas pequeñas.

---

## 12. Errores frecuentes

* Usar BGR en vez de RGB, o NHWC en vez de NCHW.
* No aplicar la orientación EXIF (las cajas salen giradas respecto a la imagen que ve el usuario).
* Mantener la proporción o añadir bandas al redimensionar (ninguno de los dos modelos lo espera).
* Normalizar el OCR con la media/desviación de ImageNet del detector (el OCR usa 0.5/0.5).
* Recortar la placa de la imagen de 576×576 en lugar de la original.
* Olvidar la sigmoide y aplicar el umbral 0.4 al logit.
* Pasar al `decoder` solo los tokens generados en vez de los 16 con relleno, o leer la última fila de logits en vez
  de la fila `t`.
* Empezar sin `start` (0) en la posición 0, o incluir `start`/`eos`/`pad` en el texto.
* Usar un `config_tflite.json` o `vocabulario.json` de otra exportación.
* Compartir un intérprete entre hilos sin sincronizar.

---

## 13. Cuando cambien los modelos

Si se reentrena o se reexporta:

* **Detector** (`RFDETR_License_Plates.ipynb`, última celda): sustituir `rf_detr_license_plates.tflite`. Mientras no
  cambie el checkpoint base, el preprocesado y las formas son los mismos.
* **OCR** (`TrOCR_Placas.ipynb`, última celda, o `scripts/exportar_ocr_tflite.py`): sustituir juntos
  `trocr_placas_int8.tflite`, `config_tflite.json` y `vocabulario.json`, que salen de la misma carpeta
  `outputs/<modelo>/tflite/`. El tamaño de entrada puede cambiar.
* En ambos casos, actualizar los SHA-256 de la sección 2.1 y la tabla de la sección 10.4 (regenerándola con
  `Pipeline_TFLite.ipynb`), y volver a pasar todas las pruebas.
