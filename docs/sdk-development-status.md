# Android SDK development status

Version 0.2.0 replaces 0.1.0 and publishes only `dev.elu:elu-analytics`, with
session replay off, under the [0.2.0 release decision](../release/decisions/0.2.0.md).
That decision defers the remaining gates below for 0.2.0 only; it does not claim them.

The first-release support boundary is Android 6+ (API 23+) for analytics,
events, identity and feature flags, with app-level core-library desugaring. Both
Views replay and annotated Compose replay require Android 10+ (API 29+) and current
server authorization. Replay is unavailable on API 23–28. Unsupported capture
remains explicitly unavailable; privacy checks are unchanged.

Current source includes bounded durable event delivery, identity/groups/properties,
explicit consent, optional memory-only analytics storage, durable feature-flag exposure metadata,
HTTPS self-host bases, Views/AppCompat replay and optional diagnostics and metrics.
Declared-region Compose replay is a separate, default-false opt-in using the
original configuration source, capture owner, queue and delivery coordinator. It
requires an exact server grant and complete privacy annotations; it does not
automatically discover all Compose inputs or classify unknown drawing. Bounded
downsampling preserves the original display viewport while limiting image size.
Generic Compose semantics and custom-drawn text remain outside Views replay.

[Hosted CI run 36773468477](https://github.com/ELU-Labs/elu-android/actions/runs/36773468477)
for PR head `25a8c9f` passed 188 instrumentation tests on API 29 and 188 on API 35,
with zero failures or skips on either platform. The original Activity A→B→A
declared-root regression passed on both. These are hosted component results; they
do not establish local exact-artifact Lab qualification or package publication.
Historical receipts remain separate from current qualification. API snapshots
must still come from actual compiled bytes and preserve old public descriptors.

Frame metrics require API 26+, while observed OS startup timing requires API 35+
and a proven incomplete-to-first-frame transition. Ambiguous startup history is
omitted. Automatic native-crash/ANR capture remains unimplemented; optional
automatic JVM reporting captures type only; richer automatic report details remain
a gap. Manual exceptions and explicit screen reporting remain available. Native
touch capture supports only single-finger observations over lawful encoded Views.

Physical-device testing is waived for this release. Remaining gates include:

- Local emulator qualification of the exact core and optional Compose artifacts,
  including installation, supported OS/R8 behavior, on-device privacy, lifecycle,
  process-death/restart, fault recovery and supported SQLite upgrades.
- Original installed-app observations joined to canonical engine readback and
  actual customer-player rendering, including state, scroll and viewport changes.
- Resource and interaction-overhead measurements using the required matched
  cohorts; component tests and a small number of frames do not replace them.
- Publication of the optional Compose module, which 0.2.0 does not publish.

HTTP responses, source guards and successful compilation alone cannot satisfy
these gates.

The candidate requires a fresh owned installation with no persisted-data import
from the 0.1.0 preview and never deletes former files. Current owned SQLite reopen
and supported schema upgrades remain separate from preview import.

Each release follows [release/README.md](../release/README.md): a signed tag,
green CI on the tagged commit, a byte-for-byte rebuild, a dry run and a manual
Publish in the Central Portal.
