# ELU Analytics — Android SDK

ELU product intelligence for Android apps. One site key, no other
configuration — behavior (privacy controls, kill switches, session replay)
is managed from your ELU dashboard and delivered as remote config. See
[`CONTRACT.md`](./CONTRACT.md) for the behavioral contract.

- **You only need a site key.** No separate analytics-provider account or API
  key is required in the app.
- Privacy controls (EU blocking, text/image masking, replay limits) are
  applied **client-side at capture time** and managed from the ELU dashboard.
- `minSdk 23` for the analytics runtime. Standalone session replay is still
  undergoing qualification and is not enabled in this checkout.

This source checkout contains the ELU-owned analytics runtime. Its standalone
release is still undergoing qualification. The published 0.1.0 release uses
the previous runtime; building this checkout does not change an already
published Maven artifact. Application code continues to use `Elu.*`.

The current standalone replay collector requires API 29 or later. API 26–28
replay compatibility remains a release requirement; it is not established by
the API 23 events and identity checks. Replay support will be documented with
the qualified release.

## Install

From Maven Central:

```kotlin
dependencies {
    implementation("dev.elu:elu-analytics:0.1.0")
}
```

Building from source instead (e.g. to try an unreleased change): clone this
repo and use a Gradle composite build in your app's `settings.gradle.kts`:

```kotlin
includeBuild("path/to/elu-android") {
    dependencySubstitution {
        substitute(module("dev.elu:elu-analytics")).using(project(":elu-analytics"))
    }
}
```

When migrating from another analytics integration, review identity continuity
and event ownership before removing it. The owned runtime has separate state;
installing it alone does not migrate an existing integration's stored identity.

For the owned source runtime, enable core library desugaring in your **app
module** so its Java time and arithmetic APIs work on Android API 23. This
configuration requires Android Gradle Plugin 8.0 or later:

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

See Android's [API desugaring documentation](https://developer.android.com/studio/write/java8-support).

## Setup

Call once from `Application.onCreate` — an **Application context is
required** (foreground-driven config refresh and screen/lifecycle
autocapture hook `Application.registerActivityLifecycleCallbacks`):

```kotlin
import android.app.Application
import dev.elu.analytics.Elu

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Elu.setup(this, "YOUR_SITE_KEY")
    }
}
```

Register that class in `AndroidManifest.xml`. Keep the app's existing
`<application>` attributes and child components:

```xml
<application
    android:name=".MyApp">
    <!-- Existing activities, services, providers, and metadata stay here. -->
</application>
```

If the app already has an `Application` subclass, add `Elu.setup(...)` to its
existing `onCreate`; do not create or register a second subclass.

`Elu.setup` is idempotent and never throws. Every `Elu.*` method is safe in
every state: before config arrives, event calls are buffered in memory
(FIFO, cap 100) and replayed once the device is cleared to send; if the
device is blocked (EU) or the site key is disabled, event calls are no-ops and
no analytics events or replay leave the device. ELU config fetches continue so
a re-enabled site can recover.

Dev override for the config endpoint:

```kotlin
import dev.elu.analytics.EluOptions

Elu.setup(this, "YOUR_SITE_KEY", EluOptions(configHost = "http://10.0.2.2:8787"))
```

`configHost` accepts an ELU HTTPS origin (`https://elu.dev` or one of its
subdomains) in every build. A debuggable build may also use a loopback origin
(`localhost`, `127.0.0.1`, `[::1]`, or the emulator host alias `10.0.2.2`, over
HTTP or HTTPS, on any port). Any other value makes `Elu.setup` log a warning
and leave the SDK idle, so a release build cannot be pointed at a third-party
endpoint.

An `http://` loopback origin also needs the app to permit cleartext traffic,
which Android 9 and later block by default: add
`android:usesCleartextTraffic="true"` to the `<application>` element of a
debug-only manifest (`src/debug/AndroidManifest.xml`), as the sample app does,
or use a debug network security configuration.

## Identity

ELU never auto-identifies. Identify users yourself when (and only when) you
know who they are:

```kotlin
Elu.identify("user-123", mapOf("plan" to "pro"))
// on logout:
Elu.reset()
```

Use the app's stable, immutable internal user ID. Do not use an email address,
name, phone number, or another direct identifier as the ID.

## Events and screens

```kotlin
Elu.capture("checkout_started", mapOf("cart_value" to 42.5))
Elu.screen("Checkout")
```

Activity-based apps get `$screen` events automatically on every foreground
Activity start. **Compose (single-Activity) apps must call `Elu.screen()`
manually** — hook your `NavController`:

```kotlin
navController.addOnDestinationChangedListener { _, destination, _ ->
    destination.route?.let { Elu.screen(it) }
}
```

## Full surface

`capture`, `identify`, `reset`, `alias`, `distinctId`, `screen`,
`register`/`unregister` (super properties), `setPersonProperties`,
`captureException`, `group`, `flush`, and feature flags: `getFeatureFlag`,
`getFeatureFlagPayload`, `isFeatureEnabled`, `reloadFeatureFlags`,
`onFeatureFlagsLoaded`, `setPersonPropertiesForFlags`,
`setGroupPropertiesForFlags`.

Every method is safe to call at any time — before setup, while config is
loading, or when analytics is disabled — it never throws and never blocks.
Behavioral details: [`CONTRACT.md`](./CONTRACT.md).

## Build notes

Library module: `elu-analytics` (namespace `dev.elu.analytics`), AGP 8.13.x,
Kotlin 2.1.x, compileSdk 36, Java 11 bytecode (JDK 17 toolchain). The build
uses strict Kotlin compiler settings and is verified in CI. R8/ProGuard:
consumer rules ship in the AAR; the SDK facade uses no reflection.

## SDK development

[SDK development status](docs/sdk-development-status.md) tracks validation gaps and related work.
