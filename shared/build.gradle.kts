import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// TensorFlow Lite C for iOS: fetched as an XCFramework and consumed through cinterop (no CocoaPods).
val tensorFlowLiteCUrl =
    "https://dl.google.com/tflite-release/ios/prod/tensorflow/lite/release/ios/release/32/20240729-115310/TensorFlowLiteC/2.17.0/0c10b3543e01f547/TensorFlowLiteC-2.17.0.tar.gz"
val tensorFlowLiteCSha256 = "9667b476015f136e5b332ce040e12822c4ac6d5c58947882ddc809cdff0fb99e"
val iosFrameworksDir = rootProject.layout.projectDirectory.dir("iosApp/Frameworks")
val tensorFlowLiteCXcframework = iosFrameworksDir.dir("TensorFlowLiteC.xcframework")
val isMacHost = System.getProperty("os.name").startsWith("Mac")

val downloadTensorFlowLiteC = tasks.register<Exec>("downloadTensorFlowLiteC") {
    group = "ios"
    description = "Downloads and verifies the TensorFlowLiteC XCFramework into iosApp/Frameworks."
    enabled = isMacHost
    inputs.property("url", tensorFlowLiteCUrl)
    inputs.property("sha256", tensorFlowLiteCSha256)
    outputs.dir(tensorFlowLiteCXcframework)
    environment("TFLITE_URL", tensorFlowLiteCUrl)
    environment("TFLITE_SHA256", tensorFlowLiteCSha256)
    environment("TFLITE_DEST", tensorFlowLiteCXcframework.asFile.absolutePath)
    commandLine(
        "sh", "-c",
        """
        set -eu
        tmp=${'$'}(mktemp -d)
        trap 'rm -rf "${'$'}tmp"' EXIT
        curl -fsSL "${'$'}TFLITE_URL" -o "${'$'}tmp/TensorFlowLiteC.tar.gz"
        echo "${'$'}TFLITE_SHA256  ${'$'}tmp/TensorFlowLiteC.tar.gz" | shasum -a 256 -c -
        tar -xzf "${'$'}tmp/TensorFlowLiteC.tar.gz" -C "${'$'}tmp"
        src=${'$'}(find "${'$'}tmp" -type d -path '*/Frameworks/TensorFlowLiteC.xcframework' | head -n 1)
        [ -n "${'$'}src" ] || { echo "TensorFlowLiteC.xcframework not found in archive" >&2; exit 1; }
        rm -rf "${'$'}TFLITE_DEST"
        mkdir -p "${'$'}(dirname "${'$'}TFLITE_DEST")"
        mv "${'$'}src" "${'$'}TFLITE_DEST"
        """.trimIndent(),
    )
}

kotlin {
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

    android {
       namespace = "botix.dev.detectorlicenseplateocr.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            api(libs.litert)
            implementation(libs.androidx.camera.core)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
