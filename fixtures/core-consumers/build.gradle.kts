plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}

// Every consumer resolves the version recorded by the checked local distribution.
val distribution = layout.projectDirectory.dir("../../build/compose-distribution")
val candidateVersion = distribution.file("version.txt").asFile.readText().trim()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?").matches(candidateVersion))
val metadataMode = providers.gradleProperty("eluMetadataMode").orElse("module").get()

subprojects {
    extra["eluCandidateVersion"] = candidateVersion
    extra["eluDistribution"] = distribution
    layout.buildDirectory.set(layout.projectDirectory.dir("build/$metadataMode"))
}
