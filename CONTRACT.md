# ELU Android owned runtime contract

This describes the current ELU-owned source candidate. It does not describe
the unused published 0.1.0 preview, and is not evidence of package publication
or production qualification. See [README.md](./README.md) for installation and
public Kotlin APIs, and [development status](./docs/sdk-development-status.md)
for outstanding release gates.

The installed native replay implementation supports two closed protocols. Native-v2
has value/encoding/buffering, current-window observation and exact-tuple durable
admission paths. It uses the exact `elu-native-wireframe-v2` Meta
discriminator, primary-pointer start/end and coordinate-free root cancellation,
and coalesced movement with at most ten samples per second. Samples must join
live lawful encoded leaf IDs and their positive clips; masked/input/placeholder
or layout-only targets are refused. Changed existing geometry uses FullSnapshot;
removal or masking of an active target requires an earlier real cancellation.
Movement chunks include the earliest logical sample in their time span and charge
each position once. Every geometry event retains the decoder's 200 ms wire-clock
spacing, independently of the monotonic capture clock. Existing frame, node, byte and minimum-duration limits remain;
capacity requests an early seal only after the original initial prefix was known
committed. The pure commit seam is descriptive and does not prove durability.
The original internal authority and queue recognize only `elu-native-wireframe-v1` / gzip /
`protocol-generation-v1` and `elu-native-wireframe-v2` / gzip /
`protocol-generation-v2`. Those definitions also restrict current config and sealed
delivery negotiation: mixed advertisements cannot pair a codec with the other
generation, and the restrictive map does not add local readback evidence. The
sealer retains one matching encoder, independent chunk domain and original privacy,
identity and version wrapper; a failed envelope does not advance its encoder.
Both codecs refuse generic append and require the original current physical use,
capture admission, source and durable accounting transaction. An ambiguous append
retains the same prepared bytes through reconciliation; unknown outcomes quarantine
the original owner. The public runtime supplies one immutable installed V1/V2
selection to both its queue and composition on every open, including reopen.
The original configuration generation selects the matching codec; a v1 grant
remains v1 even when v2 is advertised first. Crossed, unknown and uncompressed
tuples do not authorize collection. Installed support grants no remote permission
and does not change the engine's disabled-by-default production release registry.

On API 29+, an authorized v2 capture installs the original Window.Callback observer
and arms only after its exact initial geometry prefix is known durably committed.
Current hierarchy privacy, the serialized positive clip, source/session/budget and
physical ownership remain checked. Original application dispatch is forwarded once;
callback displacement withdraws collection. Single-finger pointer observations do
not claim which child handled or clicked the event. Existing v1 encoder/buffer bytes
and its chunk domain remain unchanged. The installed selection's new facade tests
remain uncompiled/unrun, and fresh exact-AAR canonical readback, customer-player
interaction/scroll rendering and resource qualification remain release gates.

## Configuration and collection authority

`Elu.setup(applicationContext, siteKey)` starts the owned runtime. Configuration
comes from ELU's site-key config endpoint. The owned transport uses the current
v2 configuration authority: capture, flags, replay and delivery require their
corresponding current authorization. A successful HTTP response by itself does
not authorize collection. Unsupported, malformed, expired, revoked or mismatched
configuration cannot open a capture or delivery path.

The public `apiHost` declaration permits an exactly matching self-hosted config
base: HTTPS with an optional regular path prefix, no explicit port, credentials,
query, fragment or trailing-dot hostname. Empty/dot path segments, backslashes,
raw non-ASCII characters, encoded separators/spaces/controls and double escapes are
refused. Prior outer-whitespace/host-case normalization and one trailing-slash
removal are retained; accepted path bytes remain exact. Undeclared bases are
refused before runtime setup. This declaration does not itself authorize ingestion;
the owned runtime's endpoint and configuration authority checks still apply.
The immutable local declaration reaches config validation, queue authorization
and the physical event, flag and replay transports. Every role retains its exact
contract path appended to the declared prefix; remote documents cannot widen the
host or prefix. Config fetching binds
the exact original site-key URI, and all transports refuse redirects. Local
customer HTTP telemetry excludes both selected SDK configuration and API hosts,
including requests outside the declared API prefix.

Cloud keeps the previous site-key storage namespace. Custom API bases use a
domain-separated hash of their canonical host plus prefix and exact site-key hash.
Existing prefixless namespaces are unchanged. Identity, consent, event/replay
queues and flag cache never migrate between bases. Opening an equivalent
normalized base reuses its original store. The protocol's
site-key hash is unchanged; no remote credential or history identity is invented.

Configuration can independently restrict features, endpoints, privacy, session
limits and replay formats. Refresh, foreground/background transitions and
configuration withdrawal are checked again at capture and delivery boundaries.
Config requests can continue while collection is disabled so authorization can
recover. There is no customer override that bypasses remote privacy policy.

Before the initial configuration decision, state-changing facade operations
have a bounded in-memory FIFO of 100 entries; overflow drops the newest entry
to preserve the already accepted identity history.
This pre-initialization buffer is not a durable offline queue. Feature-flag
reload commands are not replayed from that buffer. Invalid or unavailable
operations return safe facade defaults.

## Identity, properties and consent

The SDK creates an installation-specific anonymous identity. Customer code
calls `identify` when the user is known and `reset` on logout. Group associations,
super properties and flag-evaluation context belong to that identity context;
reset clears customer identity, groups, super properties and flag context.
The independent device ID is initially the owned anonymous ID. `reset()` preserves
it; `reset(true)` rotates it with the new anonymous ID in the same transaction.
Both preserve consent and capture-session audience history.
Captured records retain their original identity and session through offline
storage and retries. New account/context changes cannot expose a prior
account's cached flag result.

`EluOptions.personProfiles` defaults to `IDENTIFIED_ONLY`; `ALWAYS` and `NEVER`
are explicit local alternatives. Accepted identify/alias/person-property mutations
persist the processing marker. Any accepted processing-enabled event also persists
it, so group → event → resetGroups remains enabled, while group → resetGroups
without an accepted event does not. Always-mode events retain this decision on a
later identified-only reopen. Never-mode refuses person mutations before facade
projection and again at durable owner admission; flag-only context is separate.
All event categories receive the final authoritative `$device_id`, `$is_identified`
and `$process_person_profile` properties; `$epp` is removed. Numeric telemetry
input whitelists are unchanged. OS observations retain their historical interval
semantics, omit customer groups/super properties, and use receipt identity stamps.
Quota refusal, rollback and uncommitted writes cannot advance the marker or device.
Reset clears the marker; consent, ACK, flags, replay and restart preserve it.

Production openers explicitly select a mode and validate a stream-bound singleton
metadata row. The raw internal frozen-protocol conformance seam cannot reopen a
store containing this metadata. Owned families 1–6, 7–12, 25–30 and 31–36 upgrade to 37–42,
preserving core and queued bytes, audience, flags, replay and diagnostics. Device
identity begins from the current anonymous ID on upgrade; prior person mutations
cannot be inferred from flag context. Existing user/group state can enable the
next accepted event. Missing, malformed, foreign-stream or extended metadata is
refused. Versions 13–24 and 43+ remain refused; older binaries refuse 37–42.
Downgrading the owned store is unsupported.

Exposure reports use a separate closed stream/anonymous-visitor singleton. Its
sorted set retains at most 4,096 digests of flag key plus the typed Boolean,
string or missing result. Identify, consent, configuration changes, ACK and
restart retain reports; reset clears them with the anonymous identity. Each new
digest commits atomically with its accepted `$feature_flag_called` event. Quota,
withdrawal or rolled-back writes consume no report. Ambiguous completion must
reconcile the exact event and metadata together. Saturation suppresses new
exposure events without evicting old reports, blocking getters or ordinary events.
The raw frozen-protocol seam cannot produce this production exposure metadata.

Exposure properties retain the validated evaluation request ID and evaluation
time, including a missing key in an otherwise valid evaluation. No usable cache
means no exposure report. The remote/cache bit compares flags revision, flags
and payloads, excluding request/expiry clocks; bootstrap response/payload fields
are null because no customer bootstrap input is supported. The original client
lease and exact current cache read are checked again inside the event transaction.
Foreground config refresh is capped at 300 seconds (or the earlier lease-derived
renewal); a successful unchanged response reloads flags without publishing new
configuration authority or extending its original expiry. Failed flag evaluations
receive at most six owner-lifetime scheduled retries per reload cycle, with 5, 10,
20, 40, 80 and 160 second bases plus at most 20% jitter. Identity, consent, source
withdrawal and close fence late retries; an explicit reload starts a new cycle.

`optOut` immediately fences new collection and delivery work, then persists the
choice asynchronously. It purges pending replay. Previously queued events stay
paused until explicit `optIn`; already transmitted requests cannot be recalled.
`reset`, restart and a newly enabled server configuration never silently clear
user opt-out. `optIn` resumes only when current server policy also permits it,
and attempts `$opt_in` under current configuration unless its event name is null.
There is no separate delayed-until-config opt-in event queue.

Before setup, the latest valid consent choice is held in memory and reported
by `isOptedOut`. Setup persists that choice before lifecycle collection begins;
call `optOut` before `setup` when consent is initially denied. The pre-setup
choice cannot survive process death before durable storage has been opened.

`registerOnce` fills missing values or values equal to its selected default
(`"None"` by default). Identify and person-property calls accept independent
set-once values. Flag result getters return null when no currently valid result
exists; a result contains its key, enabled state, variant and payload.

## Durable delivery and restarts

With default `EluPersistenceMode.PERSISTENT`, admitted analytics and mutation records use an app-private, bounded
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

Existing-store preflight runs under the original process/file ownership lease.
Before SQLite opens the original, the SDK streams its database and any WAL or
rollback journal into a private per-store snapshot. Original SHM is never read by
SQLite during preflight: even a read-only SQLite connection can change WAL read
marks. Recovery and strict integrity/schema/identity checks run on the copy;
refusal preserves every original family member. Successful admission then opens
and revalidates the original normally. Scratch recovery removes only the exact
known regular files in that store's reserved directory; unknown entries or links
refuse startup.

This adds linear read/write I/O and temporary storage equal to the existing
physical database plus copied WAL/journal, with additional SQLite scratch sidecars.
There is no new fixed store-size ceiling. Admission checks that the copy's exact
minimum space is available, but later exhaustion still fails closed and cleans up;
it cannot guarantee space against concurrent app writes. Logical queue quotas do
not bound previously allocated SQLite pages or this temporary footprint. A process
interrupted during preflight leaves at most that one reserved snapshot; the next
original lease reclaims it before another copy. Fresh stores need no snapshot.

The unused 0.1.0 preview and unpublished aggregate-file builds have no supported
persisted-data import. Clean setup starts a fresh owned installation without
opening or deleting their old data. Retired import checkpoints are refused,
not rewritten as fresh state. This differs from the iOS owned-file recovery
path; Android supports current owned SQLite reopen and schema upgrades.

## Memory storage and explicit consent

`EluOptions.persistence` defaults to `PERSISTENT`. `MEMORY` selects a real
`:memory:` SQLite connection with memory journal and temp storage, using the same
closed schemas, admission rules and logical quotas. No analytics database,
WAL/journal/SHM, preflight copy or analytics temporary file is created in this mode.
Every identity/session/device field, queue, flag cache/exposure ledger, replay
state and diagnostics epoch disappears when the original connection closes.
Restart therefore cannot deliver its prior offline data or historical diagnostics.
These logical quotas do not establish a process RSS ceiling.

The same canonical site/API namespace and process/file lease govern both modes.
A separate, at-most-128-byte `explicit-consent-v1.json` contains only version 1,
`optedOut`, `settled` and `persistentReconciled`. A private pending replacement is
also at most 128 bytes. Either an unsettled record or an interrupted replacement
denies collection. Unknown versions, malformed bytes, links, nonprivate files or
foreign ownership refuse startup. Writes retain an existing pending inode through
truncation, sync it and its parent, then atomically rename and verify the result;
there is no unlink-before-replacement grant window.

Memory entry never opens old analytics for consent. No record plus any current
database family, abandoned owned preflight or retired owned aggregate-file presence
becomes pending denial; a genuinely fresh namespace retains its initial default.
An existing explicit record is marked not reconciled with persistent analytics.
On persistent return, the original queue commits a real opt-out privacy barrier
before restoring a settled explicit grant, even if its final bit equals the old
database bit. Only after that commit is persistent reconciliation recorded. The
barrier retires prior session/replay/diagnostic coverage, preserving event records
with their original identities. Ordinary reset cannot change consent.

Each explicit choice first writes pending consent, then commits through the owned
queue, then marks the choice settled. A pending-write error cannot prevent a
changed durable opt-out, but no grant proceeds after that error. Any unsettled
storage outcome denies the owner and retains its original namespace lease. A
memory commit reported ambiguous is read back only on that same original, fully
settled connection; exact existing state/record comparisons still apply. Failure
never substitutes a new memory database. Consent is asynchronous after setup;
process death before its writes run, or complete rejection of all filesystem
writes to the separate consent store, cannot be represented as a durable
acknowledged choice. Persistent reopen treats any supposedly reconciled record/DB
disagreement as denial. Memory mode cannot inspect a newer old-DB denial after
complete sidecar-write rejection; it retains only the last successfully recorded
explicit choice. Applications requiring a newly denied choice on every launch
must supply `optOut` before setup as well.

## Replay audience

The optional top-level configuration-v2 `replayAudience` accepts only `"new-devices"`
on an enabled document. Its absence leaves all-device eligibility unchanged;
explicit null or other values are rejected. This restriction affects replay only.

Fresh installations remember the first accepted session-bearing event in the same
SQLite transaction as that event and its session. Manual captures, screens,
exceptions and authorized automatic events count; setup, mutations and rejected
captures do not. Failed or rolled-back transactions cannot consume eligibility.
The first session identifier and start time remain installation-scoped across
identity changes, reset, consent transitions and process restart. Replay never
claims a session before analytics commits it, and cannot promote a later session.

Existing owned databases without audience history upgrade with an unknown-history
marker, which denies only new-device replay. Queued analytics and identity remain
intact. The new additive database versions are rejected by older owned binaries;
a store downgrade is unsupported. No preview/aggregate import is introduced.

## Native replay privacy

Replay is an authorized native Views wireframe stream, not screenshots or
browser DOM capture. The blanket profile masks ordinary text. The sensitive
profile can retain plain, fully visible text from supported framework Views and
the closed exact AppCompat text classes listed in README. AppCompat is optional
and adds no published runtime dependency. Capture reads the existing layout and
does not resolve pending text futures; changed layouts invalidate that frame.
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
replay requires API 29 because older versions lack public transition-alpha and
animation-matrix observations. API 26–28 replay, Compose semantics replay and
readable custom-subclass text are not supported by this candidate. Exact
AppCompat support still requires current artifact/emulator and R8 qualification.
Compose applications
can use the analytics APIs and explicit screen events. These differences remain
explicit release-scope decisions, not silently completed parity claims.

## Exceptions and native performance

Customer-installed `EluOkHttpInterceptor` observes that client's requests only.
General capture authority, consent and original identity/session/configuration
must remain current through durable enqueue; replay network-detail permission
does not authorize or disable these independent analytics events. The native
`$network_request` uses the shared method/status/response-time/initiator/failure
fields but omits URL/path, headers, bodies and exception messages. Response-time
ends at headers, with original response/failure and body ownership preserved.
The shared bound is 200 admitted observations per SDK process lifetime, never
renewed by identity/reset/consent changes; a process restart resets the bound.
ELU-owned/configuration hosts are excluded. Pending configuration, background
transitions and stale contexts drop observations; no later user inherits them.

Request telemetry preserves an existing session's last activity and cannot
extend its idle timeout. An originally sessionless request may create the first
actual capture session; a later session or expired original session rejects it.

`captureException` is explicit reporting. This candidate does not install an
automatic uncaught-exception/crash handler. Exception messages, stacks and
customer properties can contain sensitive data; callers control what they send.

Native performance sampling is disabled by default and needs both local opt-in
and current remote authorization. While a valid foreground session exists, it
can sample process proportional set size (PSS) and main-looper probe delays.
Unknown memory measurements are omitted. There is at most one outstanding
main-thread probe. Identity, consent, configuration and lifecycle changes clear
old aggregates; samples do not prolong the session idle timer.

`EluFrameMetricsOptions` separately enables public Window frame timing on API 26+
under the same remote `capturePerformance.long_tasks` permission. The original
sole resumed Activity, Window, registration timestamp and authorized session
remain bound through aggregate emission. Frame reports predating registration,
unavailable/impossible timings and withdrawn contexts are discarded. There is
one listener, no per-frame worker queue, and at most 1,000,000 accepted reports
per sample interval; each retained duration is capped at 60 seconds. Listener
removal and performance-worker settlement join SDK close completion; an
unproven cleanup fails that completion rather than claiming disposal.

Count and total/maximum duration include first-draw frames; slow-frame and
deadline-miss counts exclude them. The slow threshold is fixed at 16.666667 ms,
not display-specific jank. API 31+ exposes actual frame deadlines; earlier
versions omit that metric. API 24–25 lacks the needed original frame timestamp,
so this SDK does not collect frames below API 26. Software-rendered Windows,
separate SurfaceView buffers, multiple resumed Activities and other processes
are not covered. These scalar metrics do not enable Compose replay.

Optional process age at the first eligible observed frame is one attempt per
monitor/process lifetime and can be discarded on withdrawal. It is not an app
startup/TTID metric; the separate observed OS launch option is described below.
Both frame timing and this additional process-age field default off.

`$performance_sample` is an analytics event linked to the current anonymous or
identified user and session, with the normal applicable event context. The
monitor adds process-memory, sampled delay and optional frame metrics, not UI
text or stack traces. These are native diagnostics, not Web Vitals, complete frame/jank
measurement, or an ANR/crash detector. Applications must account for linked
performance diagnostics and readable replay text in their privacy disclosures.
Resource overhead and exact distribution behavior require the final Lab gate.

## Observed startup continuity

`EluDiagnosticsOptions(enabled = true, launchTimings = true)` enables a bounded
API 35+ public history observation. It never installs or replaces an app
completion listener. A unique exact current PID/UID/process/launch record must
first be observed incomplete, then acquire its first-frame timestamp while the
original foreground, consent and identity interval remains current. Already
completed, ambiguous, unavailable or invalid records are omitted. The local
query limits are 16 records, 101 polls and a 10-second budget checked after each
settled query (not a hard OS-call deadline); accepted launches are at
most 30 seconds. This is partial observed startup coverage, not app-wide TTID.

The default-off interval is persisted with stream, identity revision, boot and
monotonic/wall-clock floors. Explicit consent (including same-choice calls),
identity/reset, local option changes, terminal authority withdrawal and explicit
close end coverage. Routine config expiry and background do not retrospectively
invalidate a prior interval. Whole-interval ownership is required; enabling the
option after launch cannot import an earlier launch. Unknown history fails closed.

Current general capture and `capturePerformance.long_tasks` authorization plus a
live foreground receipt session are mandatory again inside the event transaction.
`$native_launch` carries numeric OS monotonic start/first-frame timestamps, duration,
reason/type, and fixed Android/source labels. Event time/session describe receipt,
not historical ownership by that session. Groups and super properties are omitted.
The dedupe watermark commits with the event; rollback and ambiguous completion
reconcile against exact durable state. Metadata-only interval changes do not
change identity/context/session or create capture-session audience history.

The raw pre-profile runtime lazily upgrades diagnostics to families 25–30 after
full validation. Production profile/exposure families 31–42 include the diagnostics table
in a closed state; creating the table does not enable diagnostics. Existing queued
data and diagnostics intervals are preserved by the profile migration, then the
normal current-option/continuity checks apply. Downgrade is unsupported.
A failed interval closure suppresses observation and cannot release the original
store lease as a successful shutdown. It retries once only after proven rollback;
unresolved failure retains process-local resource ownership. No durable guarantee
is possible if all storage writes fail and the process then dies. Automatic
uncaught/crash/ANR collection remains unimplemented; manual exceptions are separate.

## Local capture budget

`EluOptions.rateLimiting` defaults to 10 events/second and a 100-event burst.
`EluRateLimitingOptions` accepts positive finite fractional values, normalizes invalid
values to defaults, and clamps burst to at least rate. Default burst multiplication
overflow clamps to the greatest finite native Double. The selected bucket is a
strict stream-bound `capture_rate_limit` singleton, with canonical numeric payload
at most 256 bytes. Schema 43–48 adds this table to each six-way owned combination;
all supported 1–12 and 25–42 families upgrade without changing queued bytes or core
generation. Versions 13–24 remain refused. Existing-file snapshot preflight validates
its exact shape, canonical payload and stream before the live writable connection.
Old binaries cannot reopen these newer stores.

The owner rereads durable tokens/last-wall-time, otherwise uses held state, otherwise
starts full. Signed wall delta refills before the burst clamp; backwards time incurs
debt. Constructor check-only refill remembers the limited state, so reopening an
empty bucket does not issue a fresh warning. Reset, identity, group and consent
mutations do not reset the bucket. Memory uses the same table on its original
`:memory:` connection; it neither reads nor changes a dormant persistent bucket.

Original source/consent/current authority and duplicate/stale flag-exposure checks
precede the debit. Canonical validation, event enrichment, ledger capacity and queue
quota follow it. The debit is an independent transaction: later event rejection or
rollback never refunds it and cannot alter core generation. Only classified read I/O
or known no-BEGIN/proved-rollback optional metadata failure may use held arithmetic;
structural mismatch and ambiguous COMMIT stop the original owner. A subsequent
readable durable bucket supersedes held state, even after an earlier optional write
failed, matching released browser behavior. No fresh memory database is substituted.

One internally retained call attempt binds the exact command and owner across the
facade's authority renewal. Event-transaction retry remains below the debit. One
private recursion tries `$$client_ingestion_warning` on the transition to limiting,
with `$$client_ingestion_warning_message` and the fixed numeric settings text. That
warning uses normal event authority, final identity stamps and quota. Failed warning
admission is not retried during consecutive limiting. Its passive source context
cannot create/advance session activity or mark an unaccepted exposure/startup as
reported. A customer using the same event name receives no bypass. Rejected calls
remain rejected even if their warning is enqueued; delivery scheduling includes that
ordinary queue record. Native drops use `RATE_LIMITED`.

All public capture/screen/manual-exception, lifecycle, exposure, HTTP/performance and
startup event paths share this budget. Identity/local mutations and replay chunks are
exempt. Native bounded caller-value detachment happens before the serialized owner;
there are no customer capture hooks or browser console logging on Android. Additional
metadata I/O and current JVM/SQLite/artifact/device performance remain validation
requirements; authored tests are not execution evidence.

## Local replay controls

`startSessionRecording()`, `stopSessionRecording()` and
`sessionRecordingStarted()` are additive no-argument APIs. The default instance
latch is enabled; only explicit local start reverses local stop. The latch is
separate from consent/source/identity epochs and is not persisted. Pre-setup calls
are no-op/false; a stop on the published pending facade survives stack opening.
There are no browser trigger overrides or new wire/schema permissions.

Local stop immediately fences fresh reads, accepted frames and status, then joins
the original capture on the existing worker. A validated idle tail uses the same
sealer, immutable timestamps/ordinals, permit, physical use, source/identity/session,
selection and local privacy-revision checks at seal and durable admission. It never
raises elapsed duration to meet the initial minimum. A main callback interrupted
before exact View/privacy postvalidation contributes no frame and discards the
unsealed tail. No stop-time View read is used to establish permission. Restrictive
withdrawal continues to discard unsealed work and revoke delivery when required;
an uncertain append retains its original physical accounting/quarantine.

Status samples the actual original installed collector and current guards; an
ACTIVE scheduling result alone is insufficient. No synchronous View dispatch,
SQLite request or network operation occurs in the getter. Stop/start generations
cannot adopt an old callback or begin replacement before original cleanup settles.
These source contracts require actual compiled API, emulator and exact-artifact
qualification; source controls alone do not establish those results.

## Native root and viewport continuity

A closed root/viewport boundary may request recovery only after original physical
collection and durable accounting settle. Composition then joins the original
watcher disposal before any new selection. Unknown cleanup quarantines the owner.
Each replacement uses a new replay ID and sequence zero through existing queue
authority, retaining session/sample/first-start/remaining-budget state. Changed
viewport frames and unsealed boundary tails are never appended or used to satisfy
initial minimum duration. The v1 fixed-viewport contract is unchanged.

Only closed unsupported-geometry failures may retry the original collector after
full selection and authority postvalidation. No failed frame, ordinal or projection
is accepted. Existing bounded retry ticks check original consent/source/session,
local privacy and recording intent; deadlines and budgets are never extended.

After a settled root loss, at most one observer holds one canonical preparation
and local privacy witness. Its main-thread ticks inspect only the sole resumed
Activity's root/window facts. They do not select, watch, read content, poll SQLite
or renew configuration. A ready observation grants nothing: ordinary selection
and authority run again. Close/local/restrictive withdrawal invalidates admission
synchronously, and the serial worker joins any already-submitted main observation
before replacement or close completes. JVM controls are authored but unrun;
actual Android/window behavior and exact-artifact player transitions remain gates.
