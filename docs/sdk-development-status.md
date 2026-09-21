# Android SDK development status

This branch is an implementation checkpoint, not a released standalone SDK.
The source candidate is 0.2.0; the published Maven version remains 0.1.0.

The owned runtime now includes durable event delivery, identity and groups,
properties, feature flags, persistent consent controls, explicit exception
capture, privacy-restricted native Views replay, and optional foreground native
performance sampling. Replay requires API 29 or later; API 26–28 replay and
Compose replay are not qualified. Compose apps can use events and flags and
must report navigation screens explicitly.

Fresh local verification of commit `7cf916a` passed 832 JVM tests and 60
instrumentation tests on Android API 36. The device tests used a clean install
of the exact built test APK. The ordinary stock-theme label was visibly
readable on the device, and its collected snapshot excluded private inputs,
transparent/faint text and blocked content. This is collector validation;
customer-player rendering and engine readback remain separate release gates.

On 2026-09-21 the repository owner confirmed that neither 0.1.0 mobile SDK
has customers. The clean candidate therefore retires the unused preview import
readers. The public installation contract explicitly requires a fresh owned
installation with no persisted-data import from 0.1.0, and never deletes former
files. Historical release source and the immutable 0.1.0 API baseline remain
available. Current owned SQLite reopen and schema upgrades remain supported.

The production source subsequently changed, so the 60-test device result above
is historical evidence for that commit only. The clean candidate requires fresh
exact-artifact Lab fault/privacy/resource checks, source review, release checks,
publication and production verification. No final release claim follows from
compilation, local collector tests or successful HTTP responses alone.

The active instrumentation check covers clean setup, current owned SQLite
reopen/schema upgrades, refusal of unsupported state, consent, native privacy
and lifecycle behavior. The former preview replacement-continuity harness is
retired; the immutable published consumer fixture remains historical API and
artifact evidence only. Exact-distribution Lab upgrades and fault injection
remain required for the final release.

The clean pre-version candidate passed 808 JVM tests, release lint, 116 active
script tests and 62 API 36 device tests. Version 0.2.0 packages must be rebuilt
and qualified by their exact hashes; those preceding results are not a
publication or production verification claim.
