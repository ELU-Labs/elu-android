import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// R8 full mode is the AGP 8 default and what customer release builds get.
check(providers.gradleProperty("android.enableR8.fullMode").orNull in setOf(null, "true")) {
    "The optimized consumer must run with R8 full mode"
}

android {
    namespace = "dev.elu.fixture.r8"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.elu.fixture.r8"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    sourceSets["main"].kotlin.srcDir("../smoke/src/main/kotlin")
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // The app adds no ELU-specific rules: the SDK's consumer rules must be enough.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Installable on a test device; signing does not change the optimized code.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The SDK ships no lint checks; lint 8.13 crashes on this build's shared Kotlin script plugins.
    lint { checkReleaseBuilds = false }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("dev.elu:elu-analytics:${project.extra["eluCandidateVersion"]}")
}

apply(from = "../staged-artifact.gradle.kts")
apply(from = "../r8-keep-check.gradle.kts")
