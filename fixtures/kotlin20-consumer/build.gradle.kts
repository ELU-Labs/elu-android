import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.13.2"
    // The oldest Kotlin line that can read the SDK's Kotlin 2.1 metadata: a Kotlin
    // compiler reads metadata at most one minor version newer than itself.
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

val distribution = layout.projectDirectory.dir("../../build/compose-distribution")
val candidateVersion = distribution.file("version.txt").asFile.readText().trim()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(candidateVersion))

android {
    namespace = "dev.elu.fixture.kotlin20"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.elu.fixture.kotlin20"
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
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // Fail rather than warn if the compiler ever needs to tolerate unreadable metadata.
        allWarningsAsErrors.set(true)
    }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("dev.elu:elu-analytics:$candidateVersion")
}

val verifyStagedArtifacts by tasks.registering {
    doLast {
        val artifact = configurations.getByName("debugCompileClasspath").resolvedConfiguration.resolvedArtifacts
            .single { it.moduleVersion.id.group == "dev.elu" }
        check(artifact.name == "elu-analytics" && artifact.moduleVersion.id.version == candidateVersion)
        val staged = distribution.file("repository/dev/elu/elu-analytics/$candidateVersion/elu-analytics-$candidateVersion.aar").asFile
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(digest(artifact.file.readBytes()) == digest(staged.readBytes())) { "Resolved ELU artifact differs from checked local distribution" }
        val kotlinPlugin = plugins.getPlugin(org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper::class.java).pluginVersion
        check(kotlinPlugin.startsWith("2.0.")) { "This fixture must compile with Kotlin 2.0, not $kotlinPlugin" }
    }
}
tasks.named("preBuild") { dependsOn(verifyStagedArtifacts) }
