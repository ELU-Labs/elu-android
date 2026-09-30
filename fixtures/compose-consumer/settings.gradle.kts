pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

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
rootProject.name = "elu-compose-maven-consumer"
