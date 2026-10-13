import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same toolchain and release configuration as :r8, without the SDK or the
// desugaring it requires, so the measured difference is what adding the SDK costs.
android {
    namespace = "dev.elu.fixture.baseline"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.elu.fixture.baseline"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // Same release tasks as :r8, which cannot run lint on its shared Kotlin script plugins.
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}
