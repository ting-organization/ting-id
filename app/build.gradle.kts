// Ting ID for Android: Jetpack Compose on top of :core, with keys sealed by
// the phone's keystore (app/tingid/vault).

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** A version tag (v1.2.3) gives the app its version: 1.2.3 is version code 10203. */
val versionTag: String? = providers.environmentVariable("GITHUB_REF").orNull?.removePrefix("refs/tags/v")?.takeIf { Regex("^\\d+\\.\\d+\\.\\d+$").matches(it) }
val versionParts = (versionTag ?: "2.0.0").split('.').map { it.toInt() }
require(versionParts[1] < 100 && versionParts[2] < 100) { "minor and patch must be below 100, for the Android version code" }

/**
 * The nodes a new installation starts with: TING_NODES (comma-separated https
 * addresses) when the build names them, else the production nodes, which
 * check each other.
 */
val tingNodes: String = providers.environmentVariable("TING_NODES").orNull?.takeIf { it.isNotBlank() }
    ?: "https://node1.ting-node.com,https://node2.ting-node.com"

android {
    namespace = "app.tingid"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.tingid"
        // Android 11: keystore keys that need the owner's fingerprint, face or
        // screen lock for each use, with either allowed.
        minSdk = 30
        targetSdk = 36
        versionCode = versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2]
        versionName = versionParts.joinToString(".")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TING_NODES", "\"$tingNodes\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // A debug build installs beside the release one, with its own data and keys.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**", "DebugProbesKt.bin")
    }

    // No dependency metadata blob signed by Google in the APK: the release is
    // built in the open and attested by GitHub instead.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.secp256k1.jni.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
