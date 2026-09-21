# ELU Android owned runtime contract

This describes the current ELU-owned source candidate. It does not describe
the unused published 0.1.0 preview, and is not evidence of package publication
or production qualification. See [README.md](./README.md) for installation and
public Kotlin APIs, and [development status](./docs/sdk-development-status.md)
for outstanding release gates.

## Configuration and collection authority

`Elu.setup(applicationContext, siteKey)` starts the owned runtime. Configuration
comes from ELU's site-key config endpoint. The owned transport uses the current
v2 configuration authority: capture, flags, replay and delivery require their
corresponding current authorization. A successful HTTP response by itself does
not authorize collection. Unsupported, malformed, expired, revoked or mismatched
configuration cannot open a capture or delivery path.

Configuration can independently restrict features, endpoints, privacy, session
limits and replay formats. Refresh, foreground/background transitions and
configuration withdrawal are checked again at capture and delivery boundaries.
Config requests can continue while collection is disabled so authorization can
recover. There is no customer override that bypasses remote privacy policy.

Before the initial configuration decision, state-changing facade operations
have a bounded in-memory FIFO of 100 entries; overflow drops the oldest entry.
This pre-initialization buffer is not a durable offline queue. Feature-flag
reload commands are not replayed from that buffer. Invalid or unavailable
operations return safe facade defaults.

## Identity, properties and consent

The SDK creates an installation-specific anonymous identity. Customer code
calls `identify` when the user is known and `reset` on logout. Group associations,
super properties and flag-evaluation context belong to that identity context;
reset clears customer identity, groups, super properties and flag context.
Captured records retain their original identity and session through offline
storage and retries. New account/context changes cannot expose a prior
account's cached flag result.

`optOut` immediately fences new collection and delivery work, then persists the
choice asynchronously. It purges pending replay. Previously queued events stay
paused until explicit `optIn`; already transmitted requests cannot be recalled.
`reset`, restart and a newly enabled server configuration never silently clear
user opt-out. `optIn` resumes only when current server policy also permits it,
and records `$opt_in` unless its event name is null.

`registerOnce` fills missing values or values equal to its selected default
(`"None"` by default). Identify and person-property calls accept independent
set-once values. Flag result getters return null when no currently valid result
exists; a result contains its key, enabled state, variant and payload.

## Durable delivery and restarts

After admission, analytics and mutation records use an app-private, bounded
SQLite queue scoped to the exact site key. Identity changes and their queued
records commit together. Delivery uses stable stream/sequence-derived record
identities, bounded batches and retries. A validated acknowledgement must match
the submitted stream and records before local retirement. Lost acknowledgements
can cause retransmission of the same identities; HTTP 200 alone is not enough
to delete queued records.

Process restart reopens the existing owned queue and current schema upgrades
preserve it. `flush` schedules work; it cannot guarantee network completion
before Android stops the process. Queue and age limits can prevent admission
or retire expired records; the SDK does not promise unlimited offline retention.

The unused 0.1.0 preview and unpublished aggregate-file builds have no supported
persisted-data import. Clean setup starts a fresh owned installation without
opening or deleting their old data. Retired import checkpoints are refused,
not rewritten as fresh state. This differs from the iOS owned-file recovery
path; Android supports current owned SQLite reopen and schema upgrades.

## Native replay privacy

Replay is an authorized native Views wireframe stream, not screenshots or
browser DOM capture. The blanket profile masks ordinary text. The sensitive
profile can retain plain, fully visible text from supported framework Views.
Inputs stay masked in both profiles; images, WebViews and unsupported content
are hidden. Text longer than 4096 UTF-8 bytes is not truncated into a partial
capture. Unsupported rules fail closed through stronger masking or denial.

`maskView(view)` strengthens masking for that view and descendants.
`blockView(view)` excludes their content and descendants while retaining a
placeholder. Both apply for the view's lifetime and invalidate work in progress.
They cannot weaken the server policy. Previously transmitted frames cannot be
recalled. Applications should mark private display labels before showing them;
ordinary labels may contain personal information even when they are not inputs.
These replay restrictions do not sanitize customer-supplied event properties,
identity values, or exception messages.

Analytics requires API 23 with app-level core-library desugaring. Current Views
replay requires API 29. API 26–28 replay, Compose semantics replay and readable
custom/AppCompat text are not supported by this candidate. Compose applications
can use the analytics APIs and explicit screen events. These differences remain
explicit release-scope decisions, not silently completed parity claims.

## Exceptions and native performance

`captureException` is explicit reporting. This candidate does not install an
automatic uncaught-exception/crash handler. Exception messages, stacks and
customer properties can contain sensitive data; callers control what they send.

Native performance sampling is disabled by default and needs both local opt-in
and current remote authorization. While a valid foreground session exists, it
can sample process proportional set size (PSS) and main-looper probe delays.
Unknown memory measurements are omitted. There is at most one outstanding
main-thread probe. Identity, consent, configuration and lifecycle changes clear
old aggregates; samples do not prolong the session idle timer.

`$performance_sample` is an analytics event linked to the current anonymous or
identified user and session, with the normal applicable event context. The
monitor adds process-memory and sampled delay metrics, not UI text or stack
traces. These are native diagnostics, not Web Vitals, a complete frame/jank
measurement, or an ANR/crash detector. Applications must account for linked
performance diagnostics and readable replay text in their privacy disclosures.
Resource overhead and exact distribution behavior require the final Lab gate.
