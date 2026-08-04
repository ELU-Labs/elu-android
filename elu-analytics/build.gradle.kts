import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val sdkVersion =
    Regex("const val NAME: String = \"([^\"]+)\"")
        .find(file("src/main/kotlin/dev/elu/analytics/EluVersion.kt").readText())
        ?.groupValues
        ?.get(1)
        ?: error("EluVersion.NAME is the required SDK version source of truth")

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "dev.elu.analytics"
    compileSdk = 36

    defaultConfig {
        // posthog-android 3.58.0 declares minSdk 23.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    // Release/CI stays on 17. The override exists for local verification on
    // machines that have only a newer JDK; emitted bytecode remains JVM 11.
    jvmToolchain(providers.gradleProperty("eluJavaToolchainVersion").map(String::toInt).getOrElse(17))
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Exact pin — every native symbol in this wrapper was verified against
    // the android-v3.58.0 source; do not bump casually.
    implementation("com.posthog:posthog-android") {
        version { strictly("3.58.0") }
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

mavenPublishing {
    // Central Portal (https://central.sonatype.com). Publishing needs the
    // portal token + a PGP key in the environment:
    //   ORG_GRADLE_PROJECT_mavenCentralUsername / mavenCentralPassword
    //   ORG_GRADLE_PROJECT_signingInMemoryKey / signingInMemoryKeyPassword
    // then: ./gradlew publishAndReleaseToMavenCentral
    publishToMavenCentral()
    signAllPublications()

    // Version in lockstep with EluVersion.NAME (and the install snippet in
    // the ELU dashboard).
    coordinates("dev.elu", "elu-analytics", sdkVersion)

    pom {
        name.set("ELU Analytics")
        description.set("ELU Analytics SDK for Android — analytics and session replay, configured remotely by your ELU dashboard.")
        url.set("https://github.com/ELU-Labs/elu-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit/")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ELU-Labs")
                name.set("ELU Labs")
                url.set("https://elu.dev")
            }
        }
        scm {
            url.set("https://github.com/ELU-Labs/elu-android")
            connection.set("scm:git:git://github.com/ELU-Labs/elu-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/ELU-Labs/elu-android.git")
        }
    }
}

tasks.register("printSdkVersion") {
    doLast { println(sdkVersion) }
}
