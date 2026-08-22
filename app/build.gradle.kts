import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlinx.kover")
    id("dev.detekt")
}

android {
    namespace = "com.mindfulhome"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mindfulhome"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    // Compose UI
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.iconsExtended)

    // Navigation
    implementation(libs.navigation.compose)

    // Room database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager (background jobs)
    implementation(libs.work.runtime.ktx)

    // Drawable painter for Compose (render Android Drawables)
    implementation(libs.accompanist.drawablepainter)

    // LM Playground on-device inference (AIDL client)
    implementation(project(":playground-api"))

    // Backend AI (OkHttp + JSON serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Google Sign-In via Credential Manager
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)

    // Encrypted token storage
    implementation(libs.androidx.datastore)
    implementation(libs.tink.android)

    // Debug tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.testManifest)

    testImplementation(libs.junit)

    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*BuildConfig",
                    "*_Impl",
                    "*_Impl\$*",
                    "**/databinding/**",
                    "*ComposableSingletons*",
                )
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }
    }
}

detekt {
    toolVersion = "2.0.0-alpha.5"
    parallel = true
    buildUponDefaultConfig = true
    ignoreFailures = true
    basePath.set(rootProject.layout.projectDirectory)
    ignoredBuildTypes = listOf("release")
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("17")
    exclude("**/build/**")
}

// Metrics-only dump: every method’s cyclomatic complexity for the CRAP combiner.
tasks.register<Detekt>("detektCrap") {
    description = "Dump CyclomaticComplexMethod findings for CRAP scoring"
    parallel = true
    setSource(files("src/main/java", "src/main/kotlin"))
    config.setFrom(rootProject.file("config/detekt/detekt-crap.yml"))
    buildUponDefaultConfig.set(false)
    ignoreFailures.set(true)
    failOnSeverity.set(FailOnSeverity.Never)
    basePath.set(rootProject.projectDir.absolutePath)
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**")
    reports {
        checkstyle.required.set(true)
        checkstyle.outputLocation.set(file("build/reports/detekt/detektCrap.xml"))
        html.required.set(false)
        markdown.required.set(false)
        sarif.required.set(false)
    }
}

tasks.register<Exec>("crapCheck") {
    group = "verification"
    description = "Compute CRAP scores from Kover coverage + detekt complexity"
    dependsOn("koverXmlReportDebug", "detektCrap")

    val koverXml = layout.buildDirectory.file("reports/kover/reportDebug.xml")
    val detektXml = layout.buildDirectory.file("reports/detekt/detektCrap.xml")
    val reportOut = rootProject.layout.projectDirectory.file("results/crap/crap-report.md")
    val script = rootProject.layout.projectDirectory.file("scripts/crap/compute_crap.py")
    val maxCrap = providers.gradleProperty("crapMax")

    workingDir = rootProject.projectDir
    inputs.files(koverXml, detektXml, script)
    outputs.file(reportOut)

    commandLine(
        "python",
        script.asFile.absolutePath,
        "--kover",
        koverXml.get().asFile.absolutePath,
        "--detekt",
        detektXml.get().asFile.absolutePath,
        "--out",
        reportOut.asFile.absolutePath,
        "--top",
        "50",
    )

    if (maxCrap.isPresent) {
        args("--max-crap", maxCrap.get())
    }
}

// Collect full coverage for CRAP even when some unit tests assert-fail.
gradle.taskGraph.whenReady {
    val runningCrap = allTasks.any { it.path.endsWith(":crapCheck") || it.name == "crapCheck" }
    if (runningCrap) {
        tasks.named<Test>("testDebugUnitTest").configure {
            ignoreFailures = true
        }
    }
}

// Keep CRAP/detekt optional: do not fail `check` on the default detekt task.
tasks.named("check").configure {
    setDependsOn(dependsOn.filterNot {
        it is TaskProvider<*> && it.name == "detekt"
    })
}
