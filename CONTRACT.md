# ELU Mobile SDK — behavioral contract

The ELU mobile SDKs (iOS and Android) are thin wrappers over the PostHog
mobile SDKs, driven entirely by ELU remote config. The design rules:

1. The SDK is initialized with ONLY a site key — `Elu.setup` is the mobile
   analog of ELU's one-line web script tag.
2. It fetches per-site-key remote config from
   `GET https://elu.dev/v1/<siteKey>/config` and caches it on disk.
3. It ships **fail-closed compiled defaults** (EU-block ON, masking ON,
   replay off-until-config); remote config may only LOOSEN behavior
   mid-session, never pretend to retro-tighten (captured frames cannot be
   re-masked).
4. Customer code only ever touches the `Elu.*` facade — never the underlying
   provider SDK.

## 1. Config endpoint

`GET /v1/<siteKey>/config` — unauthenticated, CDN-cached (~5-minute
propagation). Always HTTP 200 with JSON.

Disabled (unknown / revoked / paused keys — not distinguished):

```json
{ "v": 1, "enabled": false }
```

Enabled:

```json
{
  "v": 1,
  "enabled": true,
  "publicToken": "phc_…",
  "host": "https://us.i.posthog.com",
  "privacy": {
    "blockEu": true,
    "maskTextInputs": true,
    "maskAllText": false,
    "maskImages": false,
    "replayNewUsersOnly": false,
    "replayMaxMinutes": 0
  }
}
```

Defaults when a privacy field is missing or unparseable: `blockEu` and
`maskTextInputs` true, everything else off, `replayMaxMinutes` 0 = unlimited
(valid range 1–60; out-of-range means unlimited). Unknown fields are ignored.
A malformed response body is a FETCH FAILURE (keep cached config / stay
pending) — never `enabled:false`.

Fetch policy: on every `setup()` (cold start), re-fetch on app-foreground if
the last success is older than 15 minutes, 10s timeout, in-process backoff,
cached config valid indefinitely.

## 2. Lifecycle state machine

States: `idle` → `pending` (setup called, no usable config yet) → `running`
(PostHog initialized) / `disabled` (config said `enabled:false`, or
EU-blocked).

- `setup(siteKey)` is idempotent (second call warns and no-ops). It records
  the first-launch marker (the `replayNewUsersOnly` probe), runs the EU
  guard, then: cached-enabled config → initialize immediately; cached
  disabled → `disabled` (keeps fetching so re-activation recovers); no cache
  (first launch ever) → `pending`, facade calls buffer in memory (FIFO,
  cap 100, drop-oldest, never persisted, never sent).
- Device-in-EU AND `blockEu` (from cached config, or the compiled default
  TRUE when no config exists yet) ⇒ blocked. Buffered ops held while the
  decision is pending flush if the fetched config says `blockEu:false` and
  are dropped if it says `blockEu:true`. Nothing leaves the device until the
  decision is made.
- Facade methods are safe in EVERY state: `pending` buffers event-class
  calls (getters return defaults), `disabled` no-ops, `running` delegates.
  Never throws, never blocks the caller.

Mid-session rules for a FRESH config while `running` — tightening applies
immediately, loosening waits for the next launch:

| Change | Action now |
|---|---|
| `enabled` true→false | opt out + stop replay; state → `disabled` |
| `enabled` false→true (PostHog never initialized) | initialize now |
| `blockEu` false→true and device is EU | opt out + stop replay; `disabled` |
| any masking tightened | stop replay for the rest of the run |
| `replayNewUsersOnly` turned on, device is returning | stop replay |
| `replayMaxMinutes` reduced | recompute budget; stop replay if exceeded |
| anything loosened | applies at next launch |

## 3. Privacy semantics

All controls act CLIENT-SIDE at capture time — masked or blocked content
never leaves the device.

- **`blockEu`** (compiled default ON, fail-closed): IANA-timezone heuristic —
  blocked when the zone is empty/unreadable, starts with `Europe/`, or is one
  of: `Asia/Nicosia, Asia/Famagusta, Atlantic/Canary, Atlantic/Madeira,
  Atlantic/Azores, Atlantic/Reykjavik, Atlantic/Faroe, Africa/Ceuta,
  America/Cayenne, America/Guadeloupe, America/Martinique, Indian/Reunion,
  Indian/Mayotte`. Blocked = the provider SDK never initializes: no network,
  no stored IDs, no events, no replay.
- **`maskTextInputs`** (default ON): mask typed input in replay. Platform
  note: the underlying mobile SDKs have a single text-masking knob that
  masks ALL rendered text (labels included), not just inputs — tighter than
  the web posture, accepted as the fail-closed direction.
- **`maskAllText`** (opt-in): strongest text masking the platform offers;
  also disables log capture into replay where the platform supports it
  (log lines routinely echo the same user text).
- **`maskImages`** (opt-in): mask all images in replay.
- **`replayNewUsersOnly`** (opt-in): replay only for devices whose
  first-launch marker was created by this install's first `setup()` call.
  Returning device ⇒ replay disabled at init; events unaffected.
- **`replayMaxMinutes`** (opt-in, 1–60, 0 = unlimited): per-session
  visual-replay budget, resumed across relaunches within the same session,
  enforced by a short poll (±5s granularity). Events keep flowing.
- Passwords are always masked by the underlying SDK regardless of settings.

## 4. Compiled defaults

The wrapper — not the customer — owns the underlying SDK's config object:
token + host come from ELU config only; screen tracking and application
lifecycle events ON; session replay ON in screenshot mode; element-
interaction autocapture, surveys, and other provider-branded extras OFF.
Replay on/off, sampling, and minimum duration remain governed server-side by
the ELU-managed project. The wrapper never calls opt-in/opt-out except on
the ELU kill-switch path (and clears a persisted opt-out at init so a
re-enabled workspace recovers).

## 5. Super properties

Registered at init AND re-registered after every `reset()` (the underlying
`reset()` clears super properties along with identity):

```
elu_sdk: "ios" | "android"
elu_sdk_version: "<wrapper semver>"
elu_facade_version: 1
```

## 6. Facade surface

`setup`, `capture`, `identify`, `reset`, `alias`, `distinctId`, `screen`,
`captureException`, `register`, `unregister`, `group`,
`setPersonProperties`, `setPersonPropertiesForFlags`,
`setGroupPropertiesForFlags`, `getFeatureFlag`, `getFeatureFlagPayload`,
`isFeatureEnabled`, `reloadFeatureFlags`, `onFeatureFlagsLoaded`, `flush`.

Identity is customer-supplied ONLY (`identify` from your auth flow, `reset`
on logout) — the SDK never auto-identifies. The facade deliberately omits
provider config access, session-recording start/stop, surveys, and
opt-in/out: exposing those would lock the public ELU API to
provider-specific semantics.

Buffered-op ordering: the pre-config buffer replays strictly FIFO once
running — including `reset`, so a pre-config logout delivers pre-reset
events under the pre-reset identity. `capture` events buffered on Android
carry their call-time timestamps; other buffered ops (and all buffered iOS
ops) are stamped at drain time, up to ~10s late on the first-ever launch
only.
