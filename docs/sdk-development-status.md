# Android SDK development status

This branch is an implementation checkpoint, not a released standalone SDK.
The source candidate is 0.2.0; the published Maven version remains 0.1.0.

The owned runtime now includes durable event delivery, identity and groups,
properties, feature flags, persistent consent controls, explicit exception
capture, privacy-restricted native Views replay, and optional foreground native
performance sampling. Replay requires API 29 or later; API 26–28 replay and
Compose replay are not qualified. Compose apps can use events and flags and
must report navigation screens explicitly.

Fresh local verification of the 0.2.0 source candidate `758e901` passed 808 JVM
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
