// Shared by the optimized consumers. The app adds no ELU keep rules, so everything
// asserted here comes from the rules shipped inside the staged AAR.
tasks.register("verifyR8KeepRules") {
    description = "Checks that R8 full mode applied the SDK's consumer rules and kept its entry points."
    dependsOn("minifyReleaseWithR8")
    doLast {
        val mapping = layout.buildDirectory.dir("outputs/mapping/release").get()
        val configuration = mapping.file("configuration.txt").asFile.readText()
        for (rule in listOf("-keep class dev.elu.analytics.Elu { public *; }", "-keep class dev.elu.analytics.EluOptions { public *; }",
                "-keepnames class androidx.appcompat.widget.AppCompatTextView", "-dontwarn android.app.ApplicationStartInfo")) {
            check(rule in configuration) { "R8 did not receive the SDK consumer rule: $rule" }
        }
        check(Regex("(?m)^-dontoptimize|^-dontshrink|^-dontobfuscate").find(configuration) == null) {
            "The optimized consumer must shrink, optimize and obfuscate"
        }
        val classes = mapping.file("mapping.txt").asFile.readLines().filter { !it.startsWith(" ") && !it.startsWith("#") }.toSet()
        for (name in listOf("dev.elu.analytics.Elu", "dev.elu.analytics.EluOptions",
                "dev.elu.analytics.internal.runtime.EluLifecycleInitializer")) {
            check("$name -> $name:" in classes) { "R8 renamed or removed $name" }
        }
        val seeds = mapping.file("seeds.txt").asFile.readLines().toSet()
        for (member in listOf("void setup(android.content.Context,java.lang.String,dev.elu.analytics.EluOptions)",
                "void capture(java.lang.String,java.util.Map)", "void identify(java.lang.String,java.util.Map)",
                "void screen(java.lang.String,java.util.Map)", "void flush()", "void reset()",
                "java.lang.String distinctId()", "java.lang.Object getFeatureFlag(java.lang.String)",
                "boolean isFeatureEnabled(java.lang.String)", "void optOut()", "boolean isOptedOut()",
                "boolean sessionRecordingStarted()", "void maskView(android.view.View)", "void blockView(android.view.View)")) {
            check("dev.elu.analytics.Elu: $member" in seeds) { "The SDK consumer rules did not keep Elu.$member" }
        }
        check("dev.elu.analytics.EluOptions: EluOptions(java.lang.String,java.lang.String)" in seeds) {
            "The SDK consumer rules did not keep the EluOptions constructors"
        }
    }
}
