# ELU Analytics — Android SDK

ELU product intelligence for Android apps. One site key, no other
configuration — behavior (privacy controls, kill switches, session replay)
is managed from your ELU dashboard and delivered as remote config. See
[`CONTRACT.md`](./CONTRACT.md) for the behavioral contract.

- **You only need a site key.** No separate analytics-provider account or API
  key is required in the app.
- Privacy controls (EU blocking, text/image masking, replay limits) are
  applied **client-side at capture time** and managed from the ELU dashboard.
- `minSdk 23`; session replay activates on API 26+.

ELU Analytics 0.1.0 exposes an ELU-owned API and currently uses PostHog's
Android runtime for managed capture and ingest. You do not need a PostHog
account or key; application code should call only `Elu.*`. This disclosure is
intentionally scoped to 0.1.0 and must be removed together with the provider
runtime dependency before a standalone release.

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

> **Already using another analytics SDK? Stop before installing ELU.** Do not
> remove or reconfigure the existing integration as part of an automated
> install. If the app already resolves this SDK's underlying analytics runtime,
> the two integrations can compete for a process-wide singleton: a second
> `setup` may no-op, disable one integration, or route events to the wrong
> project. Ask ELU to review coexistence and choose either the existing-provider
> connection or a deliberate migration. Only remove an existing integration as
> an explicit, reviewed migration step. Incompatible runtime versions fail
> Gradle resolution instead of silently overriding one another.

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

Elu.setup(this, "YOUR_SITE_KEY", EluOptions(configHost = "https://dev.elu.example"))
```

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
