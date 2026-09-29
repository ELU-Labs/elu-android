# ELU Analytics — Android SDK

ELU product intelligence for Android apps. One site key, no other
configuration — behavior (privacy controls, kill switches, session replay)
is managed from your ELU dashboard and delivered as remote config. See
[`CONTRACT.md`](./CONTRACT.md) for the behavioral contract.

- **You only need a site key.** No separate analytics-provider account or API
  key is required in the app.
- Privacy controls (EU blocking, text/image masking, replay limits) are
  applied **client-side at capture time** and managed from the ELU dashboard.
- `minSdk 23` for events, identity and feature flags. Native session replay
  requires API 29 or later and current server authorization.

This source checkout contains the ELU-owned analytics runtime. Its standalone
0.2.0 release is still undergoing qualification and is not published yet.
The published 0.1.0 release uses
the previous runtime; building this checkout does not change an already
published Maven artifact. Application code continues to use `Elu.*`.

The current standalone replay collector requires API 29 or later. API 26–28
replay compatibility remains a release requirement; it is not established by
the API 23 events and identity checks. Replay support will be documented with
the qualified release.

## Install

For the qualified 0.2.0 release, use Maven Central. This version remains
unpublished while the source candidate completes release checks:

```kotlin
dependencies {
    implementation("dev.elu:elu-analytics:0.2.0")
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

The unused 0.1.0 preview has no supported persisted-data import into this
owned release. Setup creates a fresh owned installation and leaves former
preview files untouched. It does not transfer prior identity, consent, events,
or replay, including from an unpublished aggregate-file build. Apply the user's
current consent before capturing any data.
Existing owned SQLite installations retain their identity, consent and pending
queue on reopen and supported owned schema upgrades. Source qualification is
still in progress; no new Maven release is being claimed here.

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

## Customer OkHttp request metrics (unreleased source)

Installing the interceptor explicitly enables numeric request metrics for that
customer client. It never installs a global network hook. Install it once as an
application interceptor on your existing OkHttp 4.12-compatible client:

```kotlin
import dev.elu.analytics.EluOkHttpInterceptor

val client = existingClient.newBuilder()
    .addInterceptor(EluOkHttpInterceptor())
    .build()
```

The SDK emits `$network_request` with `$network_method`, `$network_status_code`,
`$network_response_time_ms`, `$network_initiator: "okhttp"` and `$network_failed`.
Timing ends when response headers return, not when the body finishes downloading.
Unlike web request telemetry, native telemetry omits URLs and paths entirely;
headers, bodies, query strings, fragments and exception messages are never read.
Unknown methods become `UNKNOWN`. Requests to ELU-owned hosts and the configured
SDK configuration host are excluded. The interceptor returns the original
response or throws the original failure, and never reads or closes its body.

Requests need current capture authority and consent at both start and completion.
They are dropped across identity, session, consent, configuration or foreground
transitions and are checked again during the durable queue transaction. Pending
configuration is not buffered for later telemetry. The limit is 200 admitted
observations per SDK process lifetime, shared by all installed ELU interceptors;
identity/reset/consent changes do not renew it. Dropped observations consume the
bound too; only process restart resets it. Replay request-detail permission is
separate and is not needed for these metrics. A new observation can start the
installation's first actual analytics session and therefore its replay audience
history. These events are identity-linked diagnostics and belong in your privacy
disclosure. Other HTTP libraries and native redirects/individual retry attempts
are not automatically instrumented.
Once a session exists, automatic request metrics preserve its last user activity
time; background polling cannot keep an idle session alive.

## Native performance (unreleased source)

Performance sampling is disabled by default. Opt in explicitly during setup:

```kotlin
import dev.elu.analytics.EluOptions
import dev.elu.analytics.EluPerformanceOptions

Elu.setup(this, "YOUR_SITE_KEY", EluOptions(
    performance = EluPerformanceOptions(enabled = true)
))
```

The current server configuration must also authorize `capturePerformance`.
It can disable memory or main-thread stall sampling and increase the interval.
The default interval is 30 seconds, with a local minimum of 5 seconds. Sampling
runs only while the app is foregrounded with a current authorized session.
Consent, identity, configuration and lifecycle transitions discard old aggregates.
Samples do not extend the session's idle timer.

`$performance_sample` reports Android process proportional set size (PSS) in
`$memory_process_pss_bytes`, omitting unavailable measurements. A single outstanding
main-thread probe records sampled delays of at least 250 ms; count, total and
maximum delay are emitted with the configured threshold. These are sampled native
main-thread delays, not browser Web Vitals or an ANR/crash detector.

Optional frame timing needs a second explicit choice:

```kotlin
import dev.elu.analytics.EluFrameMetricsOptions

val performance = EluPerformanceOptions(
    frameMetrics = EluFrameMetricsOptions(enabled = true),
    enabled = true
)
// Pass performance in EluOptions during the existing Application setup.
```

On API 26+, one listener observes the current resumed Activity's hardware-rendered
Window through Android's public `FrameMetrics` API. It contributes numeric frame
count, total/maximum duration, first-draw count and dropped-report count to the
same `$performance_sample` interval. `$frame_slow_count` counts non-first-draw
frames longer than the explicit 16.666667 ms threshold; this fixed threshold is
not a display-specific jank classification. API 31+ also reports observed frame
deadlines and missed-deadline count. Unavailable measurements are omitted.
The server's `capturePerformance.long_tasks` setting also controls frame timing.

Frame collection is unavailable on API 23–25: API 24–25 does not expose the
timestamp needed to exclude reports from before the original permission and
listener registration. Software-rendered Windows, other Activities when more
than one is resumed, separate SurfaceView buffers and other processes are outside
this coverage. These metrics do not provide complete app rendering coverage or
make Compose UI content readable in replay. Background, consent, identity,
configuration and session changes discard pending aggregates and listeners.

Setting `processAgeAtFirstObservedFrame = true` in `EluFrameMetricsOptions` adds
one optional `$process_age_at_first_observed_frame_ms` value. This is the process
age at the first eligible observed frame, which can occur long after launch;
it is not startup time or time to initial display. Unknown values are omitted,
and an invalidated observation is not retried for a later identity.

The monitor adds no stacks or UI text, but these analytics events
are linked to the current anonymous or identified user, session and applicable
event properties. Include linked performance diagnostics in your app's privacy
disclosures. Resource overhead, actual frame delivery and customer artifact
behavior still require release qualification.

## Observed OS startup timing (unreleased source)

Startup timing is a separate default-off option on API 35+:

```kotlin
import dev.elu.analytics.EluDiagnosticsOptions

// During the existing Application setup, before the first Activity draws:
Elu.setup(this, "YOUR_SITE_KEY", EluOptions(
    diagnostics = EluDiagnosticsOptions(enabled = true, launchTimings = true)
))
```

The SDK polls Android's public `ApplicationStartInfo` history for a bounded
foreground interval. It accepts only a unique current-process Activity launch
first observed incomplete and then observed with an OS first-frame timestamp.
It leaves the application's completion listener unchanged. At most 16 records
are read per query, at most 101 queries are attempted, and a 10-second monotonic
observation budget is checked after each settled query; launches longer than
30 seconds are omitted. Backgrounding, consent
or identity changes cancel this observation.

`$native_launch` contains OS monotonic launch/first-frame nanoseconds, duration
in milliseconds, numeric launch reason/type and fixed platform/source labels.
The monotonic timestamps are not wall-clock event times. The analytics event uses
the current receipt time and an existing live session, without extending its
idle deadline or inheriting groups and super properties. No process identifiers,
names, Intent, text, stack traces or crash descriptions are sent.

A durable consent/identity interval must already cover the launch, and current
server `capturePerformance.long_tasks` permission plus general capture authority
must permit enqueue. First installation, late setup, an already-complete history
record, uncertain boot/clock continuity or missing current session can therefore
produce no startup event. This is intentionally incomplete startup coverage,
not a fabricated duration from SDK setup. API 23–34 has no equivalent collector.
Disabling either local option, explicit consent operations, identity/reset,
terminal collection denial and SDK close end the old interval. Ordinary config
expiry, background and process restart preserve it only while ownership and
clock continuity remain provable. If storage cannot settle interval withdrawal,
close fails and the original store remains occupied in that process; an OS query
that does not settle also fails close instead of claiming physical cleanup. These
checks do not promise a hard filesystem or OS-query latency bound. A total
storage-write failure followed by process death cannot be made durable.

This option does not install automatic crash/ANR or uncaught-exception collection.
Manual `captureException` remains available. The linked startup event requires
privacy disclosure. Current OS delivery, upgraded SQLite behavior and overhead
remain exact-artifact emulator and Lab release gates.

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
`register`/`registerOnce`/`unregister` (super properties), `setPersonProperties`,
`captureException`, `group`/`getGroups`/`resetGroups`, `flush`, and feature flags: `getFeatureFlag`,
`getFeatureFlagPayload`, `getFeatureFlagResult`, `isFeatureEnabled`, `reloadFeatureFlags`,
`onFeatureFlagsLoaded`, `setPersonPropertiesForFlags`,
`setGroupPropertiesForFlags`, `resetPersonPropertiesForFlags`, and
`resetGroupPropertiesForFlags`.

Every method is safe to call at any time — before setup, while config is
loading, or when analytics is disabled — it never throws and never blocks.
Behavioral details: [`CONTRACT.md`](./CONTRACT.md).

## Consent and properties

```kotlin
Elu.optOut()                         // Stop collection and persist the choice.
Elu.reset()                          // Clear user/group state; preserve consent.
Elu.optIn()                          // Resume if permitted; attempt $opt_in when config allows.
Elu.optIn(captureEventName = null)    // Resume without an opt-in event.
Elu.registerOnce(mapOf("first_source" to "invite"))
Elu.identify("user-123", mapOf("plan" to "pro"), mapOf("first_plan" to "pro"))
val result = Elu.getFeatureFlagResult("checkout")
```

`registerOnce` updates missing properties or values equal to its optional
`defaultValue` (the string `"None"` by default). Identify and person-property
calls also accept a separate set-once map. Flag results contain `key`, `enabled`,
`variant` and `payload`; unavailable or expired results are null. Account/context
changes invalidate prior flag results immediately.

If consent is initially denied, call `Elu.optOut()` before `Elu.setup(...)`.
Before setup, the latest valid `optOut()`/`optIn(...)` choice is retained in
memory; setup commits it before lifecycle collection begins. A choice made
before setup cannot survive process death until setup opens durable storage.
`isOptedOut()` reports the pending choice. Invalid opt-in event names do not
replace a pending denial. An opt-in event is attempted once under current
configuration, not queued until a future configuration becomes available.

After setup, opt-out takes effect for new work immediately and persists asynchronously.
Already transmitted requests cannot be recalled. Pending replay is purged;
previously queued events remain paused until explicit opt-in. A logout/reset
never opts a visitor back in. `flush()` schedules delivery; it does not promise
network completion before Android terminates the process.

## Native replay privacy and supported UI

Replay captures supported Android Views when the current server configuration
authorizes replay. In sensitive-text mode, ordinary framework `TextView`,
`Button`, `CheckBox`, `RadioButton`, `Switch` and `ToggleButton` text remains
readable when it is plain, fully visible, and untransformed. Styled, transformed,
clipped, transparent and custom text remain masked. Text is limited to 4096 UTF-8
bytes per view; larger values become a placeholder. Input values, images,
WebViews and unsupported/custom views stay hidden. All-text mode masks ordinary text too.
The same checks apply to exact AppCompat `AppCompatTextView`, `AppCompatButton`,
`AppCompatCheckBox`, `AppCompatRadioButton`, `AppCompatToggleButton` and
`AppCompatCheckedTextView` classes. Their custom subclasses remain masked. Text
comes from the current displayed layout; capture never waits for a pending
`setTextFuture`. Emoji/all-caps transformations and styled text remain masked.
The SDK adds no AppCompat runtime dependency. Its optional recognition uses
closed class names and framework superclasses, with consumer shrinker rules.
AppCompat 1.8.0 instrumentation and an optimized consumer remain qualification
gates for this source change; other AppCompat versions are not yet qualified.
Standard content, fit-windows and `LinearLayoutCompat` containers are supported.
Action-bar decoration is observed only as an ancestor with uncertain layout
bounds; its toolbar siblings are not captured. Material widgets, `SwitchCompat`,
custom containers and custom drawing remain opaque.
Opaque native masking rules retain all-text masking; unresolved block rules
prevent capture. Ordinary display text can contain personal information: mask
private labels before displaying them and disclose readable replay collection.
Replay masking does not sanitize customer event properties or exception messages.

Configuration v2 can restrict replay to `replayAudience: "new-devices"`.
This records only the installation's first successfully captured analytics session,
including its continuation after a process restart. Setup and identity changes do
not start that history. Events, screens and handled exceptions can start it even
when replay is disabled. Reset, sign-in changes and opt-out/opt-in do not make a
later session eligible. Ordinary analytics keeps its existing consent and authority
rules. An absent audience setting permits every otherwise authorized session.

Owned SQLite stores created before this history was recorded upgrade conservatively:
their earlier session history is unknown, so `new-devices` replay is declined while
ordinary analytics and all-device replay remain available. The additive database
upgrade preserves identity, consent and queued records. Older SDK binaries cannot
reopen the upgraded schema; downgrading the owned store is unsupported.

Strengthen privacy before a view is displayed:

```kotlin
Elu.maskView(accountDetails)   // Mask this view's and its descendants' text.
Elu.blockView(paymentPanel)   // Exclude content and descendants; keep a placeholder.
```

These restrictions last for the view's lifetime and cannot be weakened through
the SDK. They also invalidate a capture already in progress. XML tags from
other SDKs are not interpreted. A bounded registry retains up to 128 live view
restrictions. If that bound is exceeded, replay fails closed for the process;
requested restrictions are never discarded to continue recording.

Compose semantics replay and readable text from custom view subclasses
are not yet supported; keep manual `Elu.screen()` navigation events. Analytics
works on API 23+, while replay currently requires API 29+. API 26–28 replay and
Compose parity remain qualification gaps, so this source is not yet a complete
standalone customer release. No Web Vitals or browser long-task metrics are
reported as native performance data.
API 26–28 lack the public transition-alpha and animation-matrix observations
used to exclude text hidden by framework transitions; ordinary view alpha and
matrix are insufficient substitutes.

## Build notes

Library module: `elu-analytics` (namespace `dev.elu.analytics`), AGP 8.13.x,
Kotlin 2.1.x, compileSdk 36, Java 11 bytecode (JDK 17 toolchain). The build
uses strict Kotlin compiler settings and is verified in CI. R8/ProGuard:
consumer rules ship in the AAR; the SDK facade uses no reflection.

## SDK development

[SDK development status](docs/sdk-development-status.md) tracks validation gaps and related work.
