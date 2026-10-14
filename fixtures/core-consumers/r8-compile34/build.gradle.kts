import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The r8 app compiled against android-34. The SDK references a few newer framework classes
// behind API checks; its consumer rules must let R8 minify an app that cannot see them.
check(providers.gradleProperty("android.enableR8.fullMode").orNull in setOf(null, "true")) {
    "The optimized consumer must run with R8 full mode"
}

android {
    namespace = "dev.elu.fixture.r8compile34"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.elu.fixture.r8compile34"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    sourceSets["main"].kotlin.srcDirs("../smoke/src/main/kotlin", "../r8/src/main/kotlin")
    sourceSets["main"].manifest.srcFile("../r8/src/main/AndroidManifest.xml")
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // The app adds no ELU-specific rules: the SDK's consumer rules must be enough.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
