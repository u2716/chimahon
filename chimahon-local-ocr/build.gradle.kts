plugins {
    id("mihon.library")
    kotlin("android")
}

android {
    namespace = "chimahon.local.ocr"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":chimahon"))
    implementation(project(":presentation-core"))
    implementation(project(":domain"))

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.coroutines.core)

    implementation(compose.foundation)
    implementation(compose.material3.core)

    // Meiki OCR runs through ONNX Runtime for Android.
    implementation(libs.onnxruntime.android)

    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
