import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.dropshots)
    jacoco
}

android {
    namespace = "com.yufei.comboassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yufei.comboassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-beta.1"
        testInstrumentationRunner = "com.yufei.comboassistant.ComboAssistantTestRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

room { schemaDirectory("$projectDir/schemas") }
kapt { correctErrorTypes = true }
hilt {
    enableAggregatingTask = false
}
val localRobolectricSdk = rootProject.file(
    ".tooling/android-all-instrumented-15-robolectric-12650502-i7.jar",
)
tasks.withType<Test>().configureEach {
    if (localRobolectricSdk.exists()) {
        systemProperty("robolectric.dependency.dir", localRobolectricSdk.parentFile.absolutePath)
    }
}
tasks.register<JacocoReport>("jacocoDebugReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val generated = listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/*_Factory.*",
        "**/*_HiltModules*.*",
        "**/Hilt_*.*",
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(generated) },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) { exclude(generated) },
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(layout.buildDirectory) { include("jacoco/testDebugUnitTest.exec") })
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    val localRobolectricNative = rootProject.file(".tooling/nativeruntime-dist-compat-1.0.16.jar")
    if (localRobolectricNative.exists()) {
        testImplementation(libs.robolectric) {
            exclude(group = "org.robolectric", module = "nativeruntime-dist-compat")
        }
        testRuntimeOnly(files(localRobolectricNative))
    } else {
        testImplementation(libs.robolectric)
    }
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.dropshots.runtime)
    kaptAndroidTest(libs.hilt.compiler)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(platform(libs.androidx.compose.bom))
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}
