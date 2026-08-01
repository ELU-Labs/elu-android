# ELU Analytics — Android SDK

ELU product intelligence for Android apps. One site key, no other
configuration — behavior (privacy controls, kill switches, session replay)
is managed from your ELU dashboard and delivered as remote config. See
[`CONTRACT.md`](./CONTRACT.md) for the behavioral contract.

- **You only need a site key.** No PostHog account, no API keys in the app.
- Privacy controls (EU blocking, text/image masking, replay limits) are
  applied **client-side at capture time** and managed from the ELU dashboard.
- `minSdk 23`, session replay self-gates on API 26+ via the underlying SDK.

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

> **Do not use posthog-android directly alongside this SDK.** It owns the
> process-wide PostHog singleton: a second `setup` (yours or ours) no-ops,
> which can leave one of the two dark — or route your events into the wrong
> project. The dependency is pinned `strictly("3.58.0")`, so a different
> posthog-android version in your app will fail Gradle resolution rather
> than silently override. If you're migrating from a direct PostHog
> integration, remove it first.

## Setup

Call once from `Application.onCreate` — an **Application context is
required** (foreground-driven config refresh and screen/lifecycle
autocapture hook `Application.registerActivityLifecycleCallbacks`):

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Elu.setup(this, "YOUR_SITE_KEY")
    }
}
```

`Elu.setup` is idempotent and never throws. Every `Elu.*` method is safe in
every state: before config arrives, event calls are buffered in memory
(FIFO, cap 100) and replayed once the device is cleared to send; if the
device is blocked (EU) or the site key is disabled, everything is a no-op
and nothing ever leaves the device.

Dev override for the config endpoint:

```kotlin
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

Library module: `elu-analytics` (namespace `dev.elu.analytics`), AGP 8.9.x,
Kotlin 2.1.x, compileSdk 36, Java 11 bytecode (JDK 17 toolchain). The build
files were written without a local Android toolchain and are untested until
CI runs them. R8/ProGuard: consumer rules ship in the AAR; the wrapper uses
no reflection.
