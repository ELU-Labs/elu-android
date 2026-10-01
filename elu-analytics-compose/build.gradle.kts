import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.api.file.RegularFile

val sdkVersion =
    Regex("const val NAME: String = \"([^\"]+)\"")
        .find(rootProject.file("elu-analytics/src/main/kotlin/dev/elu/analytics/EluVersion.kt").readText())
        ?.groupValues
        ?.get(1)
        ?: error("EluVersion.NAME is the required SDK version source of truth")

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.vanniktech.maven.publish.base")
    id("org.cyclonedx.bom")
}

// Optional integration only. The core Views artifact has no Compose dependency.
// Also bind project-dependency/SBOM identity to the publication version.
group = "dev.elu"
version = sdkVersion

android {
    namespace = "dev.elu.analytics.compose"
    compileSdk = 36
    defaultConfig {
        // Annotation bindings are usable on 23; the core capture eligibility remains 29+.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { targetSdk = 36 }
}

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    api(project(":elu-analytics"))
    api("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
}

// The root paired-publication gate binds both modules to the same reviewed Lab export.
// Ordinary local consumers still use the distribution checker without publication.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(javadocJar = JavadocJar.Javadoc(), variant = "release"))
    publishToMavenCentral()
    signAllPublications()
    coordinates("dev.elu", "elu-analytics-compose", sdkVersion)
    pom {
        name.set("ELU Analytics Compose annotations")
        description.set("Optional original-host Compose privacy annotations for ELU Analytics. Annotations alone do not install replay capture.")
        url.set("https://github.com/ELU-Labs/elu-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit/")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ELU-Labs")
                name.set("ELU Labs")
                url.set("https://elu.dev")
            }
        }
        scm {
            url.set("https://github.com/ELU-Labs/elu-android")
            connection.set("scm:git:git://github.com/ELU-Labs/elu-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/ELU-Labs/elu-android.git")
        }
    }
}

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    componentGroup = "dev.elu"
    componentName = "elu-analytics-compose"
    componentVersion = sdkVersion
    projectType = Component.Type.LIBRARY
    includeConfigs = listOf("releaseRuntimeClasspath")
    testConfigs = emptyList()
    includeBomSerialNumber = false
    includeBuildSystem = false
    jsonOutput = layout.buildDirectory.file("reports/sbom/elu-analytics-compose-release-sbom.json")
    xmlOutput.convention(null as RegularFile?)
}
