// The phone-independent part of Ting ID: byte formats, hashing, keys and
// recovery phrases, the chain's programs, the node client and its checks.
// Plain Kotlin, so its tests run on the JVM against the same vectors as the
// chain's Rust implementation.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // libsecp256k1 (Bitcoin Core's) through JNI: the app adds the Android build
    // of the native library, the tests the desktop one.
    api(libs.secp256k1)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.secp256k1.jni.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The desktop build of libsecp256k1 is published for Java 21; the tests run on 21,
// while the module itself stays at 17 for Android.
configurations.testRuntimeClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

tasks.test {
    // The TOCS-1 vectors live with the protocol, in docs/, for other projects to use.
    systemProperty("ting.repo", rootDir.absolutePath)
    systemProperty("ting.live", providers.gradleProperty("ting.live").isPresent.toString())
    testLogging { showStandardStreams = providers.gradleProperty("ting.live").isPresent }
}
