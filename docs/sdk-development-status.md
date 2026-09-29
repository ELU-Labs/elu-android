# Android SDK development status

This branch is an implementation checkpoint, not a released standalone SDK.
The source candidate is 0.2.0; the published Maven version remains 0.1.0.

The current self-host source slice carries the locally validated `apiHost` through
v2 config loading, queue authorization and actual events/flags/replay transports.
It retains exact role paths, configuration/consent guards and redirect denial,
and isolates custom-origin stores while preserving the existing Cloud directory.
All current Kotlin runtime/test source compiles in the cached host toolchain, and
160 focused JVM tests pass, including actual transport construction and flag-cache,
identity and queue isolation. The new real SQLite isolation case is authored but
not yet compiled or run. Exact package, emulator and end-to-end results remain
required; the earlier artifact receipts below do not cover this routing slice.

The current source also implements configuration-v2 `replayAudience: "new-devices"`
using first committed capture-session history that survives restart, identity,
consent and reset. Existing owned stores without history are conservatively
ineligible for that replay restriction. Its new source tests and additive SQLite
upgrade require fresh artifact/device qualification; the artifact results below
predate this change and must not qualify the current source.

The next source slice admits a closed set of exact AppCompat widgets/containers
without a runtime dependency. Text comes from the already displayed layout so
an unresolved AppCompat text future cannot block collection. Existing privacy,
unknown-subclass and API 29 boundaries remain. New AppCompat 1.8.0 instrumentation,
real optimized consumer behavior and exact-artifact rendering are still unrun;
the prior device/R8 results below do not qualify this expansion.

Explicit customer OkHttp instrumentation is implemented as a separate source
slice. Its numeric `$network_request` telemetry uses general event authority and
original request-start identity/consent/configuration fences, with a shared
200-observation process-lifetime bound. No global hook, URLs or bodies are
captured. The current focused HTTP/facade/transaction suite passes 67 JVM tests,
including idle-session preservation across a durable reopen.
The ABI ledger and exact artifact/consumer qualification are still pending and
remain separate from the historical results below.

The current frame source slice adds explicitly enabled public Window frame
metrics on API 26+, sharing the existing authorized, passive performance sample.
Focused JVM aggregate, listener lifecycle, sampler and facade checks provide
component evidence only. Frame metrics remain unavailable below API 26; the optional process
age at the first observed frame is not startup/TTID. Fresh Android listener
delivery, API 26/31+ availability, optimized consumer/ABI checks and emulator
resource overhead remain untested; the previous AAR/device receipts below do not
qualify this source. No distribution artifact has been rebuilt for this slice.

The pending startup slice adds default-off API 35+ observed incomplete-to-first-frame
OS launch timing with a persisted consent/identity interval, passive receipt session
and atomic dedupe. It deliberately omits already-complete or ambiguous history and
requires current performance long-tasks authority. Automatic crash/ANR collection
is still absent. Its current focused host run passes 91 JVM tests, including shutdown
failure, ambiguous epoch opening, delayed authority and consent-order controls.
The held-clock and durable-denial regressions each fail against the preceding
implementation and pass with the correction. New SQLite
instrumentation is authored but not compiled or executed. There is
no rebuilt distribution artifact or current OS/emulator result for this slice.

The prior consent candidate retains the latest pre-setup choice, commits it before
lifecycle startup, and prevents a later opt-in from admitting activity submitted
during an earlier denial. Its local host verification passed 818 JVM tests,
117 release-guard tests, release lint, immutable public ABI compatibility, the
658-class candidate inventory and strict package scans. The resulting AAR is
`6a9bbc79833107e3072d3bf288315b7b8a2646d99c690d565ea3c122c9736176`.
The final instrumentation APK passes 63/63 tests on API 29 (including a small
320-pixel display), 25/25 selected minimum-runtime tests on API 23, and 63/63 on
API 36, all after clean installation with zero skips. A SystemUI OS crash dialog
blocked focus in the first API 36 attempt; its failure evidence is retained,
and the full unchanged APK passed after OS recovery and a new clean install.
An independent Maven consumer builds debug and R8 release against this exact
AAR. Exact-distribution Lab qualification, engine readback, customer-player
rendering and resource overhead remain pending. Older results below are
historical and do not substitute for fresh checks on the current source.

The owned runtime now includes durable event delivery, identity and groups,
properties, feature flags, persistent consent controls, explicit exception
capture, privacy-restricted native Views replay, and optional foreground native
performance sampling. Replay requires API 29 or later; API 26–28 replay and
Compose replay are not qualified. Compose apps can use events and flags and
must report navigation screens explicitly.

Earlier local verification of the 0.2.0 source candidate `758e901` passed 808 JVM
tests, 116 release-guard tests, release lint, immutable API compatibility checks
and strict artifact scans. Its exact instrumentation APK passed all 62 tests
after a clean install on Android API 36. The ordinary stock-theme label was
visibly readable on the device, and its collected snapshot excluded private
inputs, transparent/faint text and blocked content. This is collector
validation; customer-player rendering and engine readback remain separate
release gates.

On 2026-09-21 the repository owner confirmed that neither 0.1.0 mobile SDK
has customers. The clean candidate therefore retires the unused preview import
readers. The public installation contract explicitly requires a fresh owned
installation with no persisted-data import from 0.1.0, and never deletes former
files. Historical release source and the immutable 0.1.0 API baseline remain
available. Current owned SQLite reopen and schema upgrades remain supported.

The clean candidate still requires exact-artifact Lab fault/privacy/resource
checks, final source review, publication and production verification. No final
release claim follows from compilation, local collector tests or successful
HTTP responses alone.

The active instrumentation check covers clean setup, current owned SQLite
reopen/schema upgrades, refusal of unsupported state, consent, native privacy
and lifecycle behavior. The former preview replacement-continuity harness is
retired; the immutable published consumer fixture remains historical API and
artifact evidence only. Exact-distribution Lab upgrades and fault injection
remain required for the final release.

An independent application resolved the exact 0.2.0 AAR through a private Maven
repository and compiled debug and R8-minified release variants. Both dependency
classpaths verified AAR SHA-256
`865fc25fbb865a566d80bcfd5e469e0e4eb705b65a550363aeef81bd118fdfa4`.
This proves local package resolution and consumer compilation, not publication
to Maven Central or customer runtime qualification.
