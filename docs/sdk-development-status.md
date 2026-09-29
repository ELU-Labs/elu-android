# Android SDK development status

The source candidate is 0.2.0. Published Maven 0.1.0 remains the previous release;
this branch is not a qualified or published standalone replacement.

Current source includes owned event delivery, identity/groups/properties, profile
modes and independent device identity, durable feature-flag exposure metadata,
explicit consent, real memory-only analytics storage, strict HTTPS self-host bases
including prefixes, manual exceptions, Views/AppCompat replay, optional HTTP/frame
metrics and limited observed OS startup timing. Public replay start/stop/status,
root/viewport continuity, capture rate limiting and native-v2 touch capture now have
hosted component tests. Original capture/accounting/watchers must settle before
recovery; each new replay ID reuses the original session/sample/time budget, and
missing-root observation cannot renew source permission. The latest source change
shares one immutable v1/v2 installed selection between the public runtime's queue
and composition, retaining exact remote tuple admission and v1 fallback. Installed
v2 selection and automatic JVM report activation have subsequent hosted coverage.
The report path retains its default-false opt-in, closed server grant, original
handler, one-slot writer and passive current-session import. Real process-death
and restart checks remain pending. Fresh exact-artifact
emulator, canonical engine/player and resource qualification remain pending.

The latest retained hosted CI run `36620702825` tested PR head `423f3ec` through
synthetic merge `fb7a4caf561d22cdedcbe2b81e115493622a7890`: 1,137 JVM tests and
121 instrumentation tests on each of API 29 and API 35 passed, with zero failures
or skips. All 165 Python/source controls, build/lint, maintained API/ABI and consumer
gates passed. Its actual 2,149,051-byte AAR has SHA-256
`d98716a6dd06af71c30c176e9b8f8f9b326dc7d96b73dca34f9e219f1d493c62`
and 807 public non-synthetic JVM classes in the maintained inventory. This is
hosted component evidence, not completed local Lab or release qualification.

The earlier hosted CI run `36581976453` tested PR head `d48cca2` through its synthetic merge
`d2f87761915937a98bc80423dfabeaf2a181aba4`: 1,071 JVM tests and 113 Android
instrumentation tests passed with zero failures or skips. Build, lint, maintained
API and consumer gates passed. Its actual 2,074,770-byte AAR (SHA-256
`0964a00000a75a4d1be596d5da6a46038ab616cfe938eb92b6cd06dda3e630af`)
has 777 JVM classes. That artifact still selects v1 in the public runtime; it does
not qualify the subsequent installed-v2 selection or the final release. API
snapshots must come from actual compiled bytes, with old public descriptors preserved.

The later durability run `36609453116` at `e06aa9e` executed 1,117 JVM tests, with one
unrelated first-activity watcher-reuse assertion failing; all 22 added durability
methods passed. Both API 29 and API 35 instrumentation jobs succeeded. This does
not cover the subsequent activation source or establish final Lab qualification.

Views replay still enforces API 29+. API 26–28 readable replay remains an unresolved
support requirement; the privacy transition guard has not been weakened. Generic
Compose/custom-drawn content remains opaque. Frame metrics require API 26+, while
startup requires API 35+ and an observed incomplete-to-first-frame transition with
proven continuity. Already-complete/ambiguous startup history is omitted. Automatic
native-crash/ANR capture remains unimplemented; optional automatic JVM reporting
currently captures type only, leaving richer detail parity unresolved. Native touch source supports only
single-finger observations over lawful encoded Views; real drag/scroll, privacy
crossing, rotation and customer-player rendering still require exact-artifact Lab
qualification. Manual exceptions and explicit screen reporting remain available.

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
