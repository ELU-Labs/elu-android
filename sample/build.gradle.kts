import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.elu.analytics.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.elu.analytics.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("eluJavaToolchainVersion").map(String::toInt).getOrElse(17))
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val baselineVersion = providers.gradleProperty("eluBaselineVersion")

dependencies {
    if (baselineVersion.isPresent) {
        implementation("dev.elu:elu-analytics:${baselineVersion.get()}")
    } else {
        implementation(project(":elu-analytics"))
    }
}

tasks.register("verifyBaselineArtifact") {
    group = "verification"
    description = "Verifies the immutable published 0.1.0 AAR selected by the sample."
    onlyIf { baselineVersion.orNull == "0.1.0" }
    doLast {
        val artifact = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .single { it.moduleVersion.id.group == "dev.elu" && it.name == "elu-analytics" }
            .file
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == "aeab6cede8da582626505b019a5dc1574d06241b1f1905583ac8e3922d215b8d") {
            "Published 0.1.0 AAR checksum mismatch: $digest"
        }
    }
}
