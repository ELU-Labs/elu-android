import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    jvmToolchain(17)
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
    coordinates("dev.elu", "elu-analytics", "0.1.0")

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
