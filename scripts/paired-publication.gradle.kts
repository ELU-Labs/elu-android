import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

// The pinned publishing plugin uploads the combined deployment only after a
// successful build. Validate the actual staged repository before that boundary.
val modules = listOf("elu-analytics", "elu-analytics-compose")
val publishPaths = modules.map { ":$it:publishMavenPublicationToMavenCentralRepository" }
val preparePaths = modules.map { ":$it:prepareMavenCentralPublishing" }.toSet()
val automaticPaths = modules.map { ":$it:enableAutomaticMavenCentralPublishing" }.toSet()
val releaseTag = providers.environmentVariable("RELEASE_TAG").orElse("")
val checkEvidence = tasks.register<Exec>("verifyPairedReleaseEvidence") {
    group = "verification"
    workingDir(rootDir)
    commandLine("python3", "scripts/verify-paired-publication.py", "--tag", releaseTag.get())
}
val checkPublished = tasks.register<Exec>("verifyStagedPairedPublication") {
    group = "verification"
    dependsOn(publishPaths)
    workingDir(rootDir)
    commandLine("python3", "scripts/verify-paired-publication.py", "--tag", releaseTag.get(), "--published")
}
tasks.register("publishPairedRelease") {
    group = "publishing"
    description = "Publishes the exact reviewed core and Compose distribution together."
    dependsOn(checkPublished)
    dependsOn(modules.map { ":$it:publishAndReleaseToMavenCentral" })
}
gradle.projectsEvaluated {
    modules.forEach { name ->
        project(":$name").tasks.configureEach {
            if (this is AbstractPublishToMaven || this.name in setOf(
                    "prepareMavenCentralPublishing", "enableAutomaticMavenCentralPublishing", "signMavenPublication")) {
                dependsOn(checkEvidence)
            }
        }
    }
}
gradle.taskGraph.whenReady {
    val publications = allTasks.filterIsInstance<AbstractPublishToMaven>()
    val centralTasks = allTasks.filter { it.name in setOf(
        "prepareMavenCentralPublishing", "enableAutomaticMavenCentralPublishing", "dropMavenCentralDeployment") }
    if (publications.isNotEmpty() || centralTasks.isNotEmpty()) {
        check(!gradle.startParameter.isConfigurationCacheRequested) {
            "Reviewed paired publication requires --no-configuration-cache"
        }
        check(publications.map { it.path }.toSet() == publishPaths.toSet() && publications.size == 2 &&
            publications.all { it is PublishToMavenRepository && it.repository.url.scheme == "file" } &&
            hasTask(":publishPairedRelease") && hasTask(":verifyPairedReleaseEvidence") &&
            hasTask(":verifyStagedPairedPublication") &&
            centralTasks.map { it.path }.toSet() == preparePaths + automaticPaths) {
            "Use publishPairedRelease with the original signed paired Lab export; partial or alternate publication is refused"
        }
    }
}
