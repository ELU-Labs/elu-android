pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

// Consumers of the exact locally staged Maven distribution, never the SDK project.
val metadataMode = providers.gradleProperty("eluMetadataMode").orElse("module").get()
require(metadataMode in setOf("module", "pom")) { "Expected module or pom metadata mode" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "checkedLocalDistribution"
                    url = uri("../../build/compose-distribution/repository")
                    metadataSources {
                        if (metadataMode == "module") gradleMetadata()
                        else { mavenPom(); ignoreGradleMetadataRedirection() }
                    }
                }
            }
            filter { includeGroup("dev.elu") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "elu-core-maven-consumers"
// r8: optimized release app (R8 full mode) that runs the device smoke and is measured for size.
// r8-baseline: the same minimal app without the SDK, for the size delta.
// okhttp5: an app that already uses OkHttp 5.x, minified, with a bytecode linkage check.
// java: a Java-only app without the Kotlin Gradle plugin.
include(":r8", ":r8-baseline", ":okhttp5", ":java")
