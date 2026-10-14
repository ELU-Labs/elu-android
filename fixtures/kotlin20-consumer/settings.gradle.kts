pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

// A separate build: one Gradle build cannot mix Kotlin plugin versions.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "checkedLocalDistribution"
                    url = uri("../../build/compose-distribution/repository")
                    metadataSources { gradleMetadata() }
                }
            }
            filter { includeGroup("dev.elu") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "elu-kotlin20-maven-consumer"
