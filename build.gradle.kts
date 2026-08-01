plugins {
    // vanniktech maven-publish 0.37.0 requires AGP >= 8.13.0 (checked at apply).
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
