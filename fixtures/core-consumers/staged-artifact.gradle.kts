import java.security.MessageDigest

// Shared by the SDK consumers: the resolved ELU AAR must be the exact staged artifact.
// No top-level properties: Android lint cannot analyze them in applied scripts.
tasks.register("verifyStagedArtifacts") {
    val candidateVersion = project.extra["eluCandidateVersion"] as String
    val distribution = project.extra["eluDistribution"] as Directory
    doLast {
        val observations = mutableListOf<String>()
        for (configurationName in listOf("releaseCompileClasspath", "releaseRuntimeClasspath")) {
            val artifacts = configurations.getByName(configurationName).resolvedConfiguration.resolvedArtifacts
                .filter { it.moduleVersion.id.group == "dev.elu" }
            check(artifacts.map { it.name } == listOf("elu-analytics")) { "Only the core ELU artifact may resolve: $artifacts" }
            val artifact = artifacts.single()
            check(artifact.moduleVersion.id.version == candidateVersion)
            val staged = distribution.file("repository/dev/elu/elu-analytics/$candidateVersion/elu-analytics-$candidateVersion.aar").asFile
            fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val actual = digest(artifact.file.readBytes())
            check(actual == digest(staged.readBytes())) { "Resolved ELU artifact differs from checked local distribution" }
            observations += "$configurationName ${artifact.moduleVersion.id} $actual"
        }
        val report = layout.buildDirectory.file("reports/staged-artifacts.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(observations.joinToString("\n", postfix = "\n"))
    }
}
tasks.named("preBuild") { dependsOn("verifyStagedArtifacts") }
