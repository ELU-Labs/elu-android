plugins {
    // vanniktech maven-publish 0.37.0 requires AGP >= 8.13.0 (checked at apply).
    id("com.android.library") version "8.13.2" apply false
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

tasks.register<Exec>("checkConformanceFixtures") {
    group = "verification"
    description = "Validates provisional Android 0.1.0 behavior fixtures."
    commandLine("python3", "scripts/validate-conformance-fixtures.py")
}

tasks.register<Exec>("checkZeroBrandScanner") {
    group = "verification"
    description = "Runs the zero-brand scanner's dependency-free tests."
    commandLine("python3", "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_*.py")
}

tasks.register<Exec>("checkZeroBrandRatchet") {
    group = "verification"
    description = "Rejects forbidden-identifier debt beyond the 0.1.0 wrapper baseline."
    commandLine(
        "python3",
        "scripts/zero-brand-gate.py",
        "--mode",
        "ratchet",
        "--baseline",
        "scanner/zero-brand-ratchet.json",
    )
}
