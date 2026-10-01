import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

val metadataMode = providers.gradleProperty("eluMetadataMode").orElse("module").get()
require(metadataMode in setOf("module", "pom"))
layout.buildDirectory.set(layout.projectDirectory.dir("build/$metadataMode"))
val distribution = layout.projectDirectory.dir("../../build/compose-distribution")
val candidateVersion = distribution.file("version.txt").asFile.readText().trim()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(candidateVersion))

android {
    namespace = "dev.elu.analytics.composeconsumer"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.elu.analytics.composeconsumer"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
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
    implementation("dev.elu:elu-analytics-compose:$candidateVersion")
}

// This is an artifact consumer, not a project/composite-build substitute.
val verifyStagedArtifacts by tasks.registering {
    doLast {
        val observations = mutableListOf<String>()
        for (configurationName in listOf("debugCompileClasspath", "debugRuntimeClasspath")) {
            val artifacts = configurations.getByName(configurationName).resolvedConfiguration.resolvedArtifacts
                .filter { it.moduleVersion.id.group == "dev.elu" }
            check(artifacts.map { it.name }.sorted() == listOf("elu-analytics", "elu-analytics-compose"))
            for (artifact in artifacts) {
                check(artifact.moduleVersion.id.version == candidateVersion)
                val staged = distribution.file("repository/dev/elu/${artifact.name}/$candidateVersion/${artifact.name}-$candidateVersion.aar").asFile
                fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                val actual = digest(artifact.file.readBytes())
                check(actual == digest(staged.readBytes())) { "Resolved ELU artifact differs from checked local distribution" }
                observations += "$configurationName ${artifact.moduleVersion.id} $actual"
            }
        }
        val report = layout.buildDirectory.file("reports/staged-artifacts.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(observations.joinToString("\n", postfix = "\n"))
    }
}
tasks.named("preBuild") { dependsOn(verifyStagedArtifacts) }
