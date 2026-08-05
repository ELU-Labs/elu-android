import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.elu.analytics.upgradeevidence"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.elu.analytics.upgradeevidence"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        animationsDisabled = true
    }
}

kotlin {
    jvmToolchain(providers.gradleProperty("eluJavaToolchainVersion").map(String::toInt).getOrElse(17))
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val dependencySelection = providers.gradleProperty("eluUpgradeDependency").getOrElse("candidate")
val sourceVersion = providers.gradleProperty("eluUpgradeSourceVersion").getOrElse("0.1.0")

check(dependencySelection in setOf("published", "candidate")) {
    "eluUpgradeDependency must be 'published' or 'candidate'"
}

dependencies {
    if (dependencySelection == "published") {
        implementation("dev.elu:elu-analytics:$sourceVersion")
    } else {
        implementation(project(":elu-analytics"))
    }

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val dependencySelectionReport =
    layout.buildDirectory.file("reports/upgrade-dependency-selection.json")
val fixtureApk = layout.buildDirectory.file("outputs/apk/debug/upgrade-evidence-debug.apk")

tasks.register("verifyUpgradeDependencySelection") {
    group = "verification"
    description = "Verifies and records the dependency selected by the upgrade fixture."
    dependsOn("assembleDebug")
    inputs.property("dependencySelection", dependencySelection)
    inputs.property("sourceVersion", sourceVersion)
    inputs.file(fixtureApk).withPropertyName("fixtureApk")
    outputs.file(dependencySelectionReport)

    doLast {
        val runtimeClasspath = configurations.getByName("debugRuntimeClasspath")
        val componentIds = runtimeClasspath.incoming.resolutionResult.allComponents.map { it.id }
        val publishedComponents =
            componentIds.filterIsInstance<ModuleComponentIdentifier>().filter {
                it.group == "dev.elu" && it.module == "elu-analytics"
            }
        val candidateComponents =
            componentIds.filterIsInstance<ProjectComponentIdentifier>().filter {
                it.projectPath == ":elu-analytics"
            }

        val baselineArtifactSha256: String?
        val resolvedKind: String
        val resolvedIdentity: String
        when (dependencySelection) {
            "published" -> {
                check(sourceVersion == BASELINE_VERSION) {
                    "published upgrade evidence must use $BASELINE_VERSION"
                }
                check(publishedComponents.size == 1 && candidateComponents.isEmpty()) {
                    "published selection did not resolve exactly dev.elu:elu-analytics:$sourceVersion"
                }
                val component = publishedComponents.single()
                check(component.version == sourceVersion) {
                    "published selection resolved ${component.version}, expected $sourceVersion"
                }
                val artifact =
                    runtimeClasspath.resolvedConfiguration.resolvedArtifacts.single {
                        it.moduleVersion.id.group == "dev.elu" &&
                            it.name == "elu-analytics" &&
                            it.moduleVersion.id.version == sourceVersion
                    }.file
                baselineArtifactSha256 = sha256(artifact.readBytes())
                check(baselineArtifactSha256 == BASELINE_AAR_SHA256) {
                    "published $sourceVersion AAR checksum mismatch: $baselineArtifactSha256"
                }
                resolvedKind = "module"
                resolvedIdentity = "dev.elu:elu-analytics:$sourceVersion"
            }

            "candidate" -> {
                check(candidateComponents.size == 1 && publishedComponents.isEmpty()) {
                    "candidate selection did not resolve exactly project :elu-analytics"
                }
                baselineArtifactSha256 = null
                resolvedKind = "project"
                resolvedIdentity = ":elu-analytics"
            }

            else -> error("unsupported upgrade dependency selection: $dependencySelection")
        }

        val apkSha256 = sha256(fixtureApk.get().asFile.readBytes())
        val artifactDigestJson = baselineArtifactSha256?.let { "\"$it\"" } ?: "null"
        val report =
            """
            {
              "schemaVersion": 1,
              "selection": "$dependencySelection",
              "sourceVersion": "$sourceVersion",
              "resolvedKind": "$resolvedKind",
              "resolvedIdentity": "$resolvedIdentity",
              "baselineArtifactSha256": $artifactDigestJson,
              "fixtureApkSha256": "$apkSha256"
            }
            """.trimIndent() + "\n"
        dependencySelectionReport.get().asFile.apply {
            parentFile.mkdirs()
            writeText(report, Charsets.UTF_8)
        }
    }
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

val BASELINE_VERSION = "0.1.0"
val BASELINE_AAR_SHA256 = "aeab6cede8da582626505b019a5dc1574d06241b1f1905583ac8e3922d215b8d"
