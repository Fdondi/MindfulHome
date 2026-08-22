import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Vendored client SDK from https://github.com/Fdondi/LMPlayground-server (playground-api).

plugins {
    id("com.android.library")
}

android {
    namespace = "com.druk.lmplayground.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    androidResources {
        enable = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
