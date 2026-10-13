plugins {
    // A Java-only app: no Kotlin Gradle plugin, no Kotlin sources.
    id("com.android.application")
}

android {
    namespace = "dev.elu.fixture.java"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.elu.fixture.java"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("dev.elu:elu-analytics:${project.extra["eluCandidateVersion"]}")
}

apply(from = "../staged-artifact.gradle.kts")
