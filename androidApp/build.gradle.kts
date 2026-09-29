import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "botix.dev.detectorlicenseplateocr"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "botix.dev.detectorlicenseplateocr"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    androidResources {
        // Models are memory-mapped straight from the APK, which needs them stored uncompressed.
        noCompress += "tflite"
        // The app reads plates with plate_ocr.tflite (OcrEngine.FAST_PLATE_OCR), so the TrOCR files stay out of the
        // APK: they are only used by the device tests that compare both OCRs (Pipeline.md 2.1). plate_ocr_float.tflite
        // is the iOS copy of the OCR (see scripts/dequantize_hybrid_weights.py).
        // The other entries are aapt's defaults, which a custom pattern would otherwise replace.
        ignoreAssetsPatterns += listOf(
            "!trocr_placas.tflite", "!trocr_placas_int8.tflite", "!plate_ocr_float.tflite", "!config_tflite.json", "!vocabulario.json", "!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*",
            "!CVS", "!thumbs.db", "!picasa.ini", "!*~",
        )
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}