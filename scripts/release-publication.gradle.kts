import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

// Only dev.elu:elu-analytics is published. The pinned publishing plugin signs it
// into a local staging repository and uploads that deployment after a successful
// build, without automatic release: a maintainer clicks Publish in the Central
// Portal. Validate the exact staged bytes before that boundary.
val publishedModule = ":elu-analytics"
val publishPath = "$publishedModule:publishMavenPublicationToMavenCentralRepository"
val preparePath = "$publishedModule:prepareMavenCentralPublishing"
val releaseTag = providers.environmentVariable("RELEASE_TAG").orElse("")
val checkCandidate = tasks.register<Exec>("verifyReleaseCandidate") {
    group = "verification"
    workingDir(rootDir)
    commandLine("python3", "scripts/verify-release-publication.py", "--tag", releaseTag.get())
}
val checkStaged = tasks.register<Exec>("verifyStagedPublication") {
    group = "verification"
    dependsOn(publishPath)
    workingDir(rootDir)
    commandLine("python3", "scripts/verify-release-publication.py", "--tag", releaseTag.get(), "--published")
}
tasks.register("stageCentralRelease") {
    group = "publishing"
    description = "Uploads the reviewed dev.elu:elu-analytics release to Maven Central for a manual Publish."
    dependsOn(checkStaged)
    dependsOn("$publishedModule:publishToMavenCentral")
}
gradle.projectsEvaluated {
    listOf(":elu-analytics", ":elu-analytics-compose").forEach { name ->
        project(name).tasks.configureEach {
            if (this is AbstractPublishToMaven || this.name in setOf(
                    "prepareMavenCentralPublishing", "enableAutomaticMavenCentralPublishing", "signMavenPublication")) {
                dependsOn(checkCandidate)
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
            "Reviewed publication requires --no-configuration-cache"
        }
        check(publications.map { it.path } == listOf(publishPath) &&
            publications.all { it is PublishToMavenRepository && it.repository.url.scheme == "file" } &&
            hasTask(":stageCentralRelease") && hasTask(":verifyReleaseCandidate") &&
            hasTask(":verifyStagedPublication") &&
            centralTasks.map { it.path }.toSet() == setOf(preparePath)) {
            "Use stageCentralRelease with the signed release tag; partial, automatic or alternate publication is refused"
        }
    }
}
