# Android SDK development status

The source candidate is 0.2.0. Published Maven 0.1.0 remains the previous release;
this branch is not a qualified or published standalone replacement.

Current source includes owned event delivery, identity/groups/properties, profile
modes and independent device identity, durable feature-flag exposure metadata,
explicit consent, real memory-only analytics storage, strict HTTPS self-host bases
including prefixes, manual exceptions, Views/AppCompat replay, optional HTTP/frame
metrics and limited observed OS startup timing. Public local replay start/stop/status controls are a new source-only slice;
its Kotlin tests are authored but not yet compiled or run. Automatic root/viewport
continuity is a subsequent source-only slice: original capture/accounting/watchers
must settle, each new replay ID reuses the original session/sample/time budget,
and missing-root observation cannot renew source permission. Its new JVM controls
also remain uncompiled/unrun. Hosted limiter testing
has begun separately; its native migration fixture required a trusted-key setup
correction. Neither result is replay-controls qualification. The current exact
AAR/API inventory, consumer, emulator and resource results remain pending.

Hosted CI at commit `1d2fd17` ran 962 JVM tests with zero failures or ignored tests
and 94 Android instrumentation tests with zero failures, errors or skips. Build,
lint and consumer checks passed. The actual 1,905,062-byte AAR (SHA-256 prefix
`0e9b4aca`) produced a 726-class inventory; generated API review is separate. These
results cover the source before the limiter. They do not qualify the limiter/replay-controls slices or
the final release artifact. Public API snapshots must come from actual compiled
bytes, with old public descriptors preserved.

Views replay still enforces API 29+. API 26–28 readable replay remains an unresolved
support requirement; the privacy transition guard has not been weakened. Generic
Compose/custom-drawn content remains opaque. Frame metrics require API 26+, while
startup requires API 35+ and an observed incomplete-to-first-frame transition with
proven continuity. Already-complete/ambiguous startup history is omitted. Automatic
fatal crash/ANR capture and touch replay are not implemented; manual exceptions and
explicit screen reporting remain available.

The owner has waived physical-device testing for this release. Fresh simulator/
emulator qualification of exact artifacts is still required, including supported
OS/R8 behavior, installation, privacy/fault/upgrade/resource checks, canonical engine
readback and customer-player rendering. HTTP responses, source guards and successful
compilation alone cannot satisfy those gates.

Historical component receipts remain retained. For example, the prior consent AAR
`6a9bbc79833107e3072d3bf288315b7b8a2646d99c690d565ea3c122c9736176`
had 818 JVM, 117 release-guard and 63/63 API 29, 25/25 selected API 23, 63/63 API 36
instrumentation results plus a debug/R8 Maven consumer. Those earlier results do not
qualify newer routing, profiles, exposure, memory or limiter source. Later exact
package/consumer receipts also remain separate from current final qualification.

On 2026-09-21 the owner confirmed there are no customers of either 0.1.0 mobile SDK.
The candidate requires a fresh owned installation with no persisted-data import
from that preview and never deletes former files. Current owned SQLite reopen and
supported schema upgrades remain required and tested separately from preview import.

Final release still requires current evidence, independent review and the gated
publication process. No publication or production verification is claimed here.
