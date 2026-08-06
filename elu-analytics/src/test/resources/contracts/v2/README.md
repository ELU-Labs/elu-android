# ELU SDK config and replay contract v2

This directory is the canonical cross-platform contract for ELU SDK
configuration negotiation and replay transport version 2. It is a closed,
provider-neutral protocol shared by the browser, Android, iOS, and the ELU
engine.

The semantic contract version is frozen at `2.0.0`. Top-level config, replay,
request, acknowledgement, and version payloads carry `schemaVersion: 2`.
Event, mutation, and feature-flag channels continue to use contract `1.0.0`
and schema version `1`; config v2 declares those versions explicitly.

The transport profile is `specified-not-wired`. These schemas, fixtures,
checksums, and executable vectors define compatibility but do not claim that a
public SDK sends replay v2 or that the engine accepts it. Replay v1 is not
accepted by the standalone replay endpoint. Live engine readback remains a
blocking gate.

## Configuration

- `GET /sdk/v2/{siteKey}/config` is the owned configuration endpoint.
- The site key is an opaque public routing credential and is the only
  credential on the config request.
- Enabled config contains only ELU-controlled HTTPS endpoints.
- Event and flag endpoints remain `/v1/events` and `/v1/flags`; replay v2 uses
  `/v2/replay`.
- Enabled config declares exact event, mutation, flag, and replay protocol
  versions. Replay also declares a bounded protocol generation.
- Replay transport capability is an array of exact `(codec, compression)`
  pairs. It is not the Cartesian product of independent lists.
- A runtime may authorize replay only when it understands the exact replay
  contract, schema, generation, and one advertised pair that is also present
  in its local readback-proven registry.
- Raw config is bounded to 65,536 UTF-8 bytes before parsing. Unknown fields,
  duplicate decoded keys, unsupported versions, malformed values, untrusted
  endpoint roles, or expired/revoked/disabled config fail closed. Duplicate
  detection runs on raw JSON, so literal duplicates and escaped-equivalent
  spellings of the same key are both rejected before ordinary parsing.
- Config uses a CORS `GET` with the public site key in the path and fetch
  credentials omitted and ordinary `cache: "default"` fetch semantics. It does
  not require preflight, but the endpoint also answers unauthenticated
  `OPTIONS`. Both `GET` and `OPTIONS` responses use
  `Access-Control-Allow-Origin: *`, allow `GET, OPTIONS`, and omit
  `Access-Control-Allow-Credentials`.
- A successful enabled, disabled, or revoked response uses
  `public, max-age={ttl}, s-maxage={ttl}, must-revalidate`, where `ttl` is the
  non-negative integer
  `floor(min(300, remaining issuance lifetime, revocation budget))`. Both age
  directives therefore have a hard 300-second ceiling, never outlive
  `expiresAt`, and never outlive the revocation budget. The response never uses
  `stale-while-revalidate`, and the SDK rejects bytes at or after `expiresAt`
  regardless of an intermediary cache.
- Network errors and HTTP `429`, `500`, `502`, `503`, or `504` are transient
  failures, not config documents. HTTP transient and emergency read-failure
  responses use `no-store, max-age=0, s-maxage=0`. Pending work is preserved,
  but neither stale nor missing config may authorize a send.

## Replay authorization and CORS

Replay v2 places the public routing site key only in the HTTP
`Authorization` header as `Bearer {siteKey}`. There is no query fallback,
cookie authorization, provider credential, or beacon delivery. Replay uses a
cross-origin `fetch` `POST` with credentials omitted, JSON content type, and
identity HTTP content encoding.

Because the authorization header is not CORS-safelisted, replay v2 requires
an unauthenticated `OPTIONS` preflight. The endpoint allows `POST, OPTIONS`
and the `Authorization, Content-Type` request headers, returns
`Access-Control-Allow-Origin: *`, and exposes `Retry-After` on the `POST`
response. These exact semantics are transport metadata in `manifest.json`;
they remain specified but are not runtime wiring.

The replay request identity carries neither the config revision nor the replay
protocol generation. On receipt, the engine resolves and authorizes the
server-current site route from the bearer site key, then records the accepted
protocol generation server-side. That server record does not prove which
generation captured the client payload. Every queued or prepared client row
therefore persists its capture generation. An exact generation match preserves
the row; any generation change purges it before a send attempt, even when the
codec/compression pair is unchanged. An unsupported current generation purges
older rows and fails closed. The chunk's policy revision,
`effectivePolicyHash`, and `maskingProfileHash` are capture-time audit receipts;
they do not prove that a public site-key caller is trusted.

## Replay envelope

A replay chunk carries immutable capture-time identity and context evidence:

```text
identity
  anonymousId: non-empty string
  userId: non-empty string or null
  revision: safe non-negative integer

contextRevision: safe non-negative integer
```

Identity or context mutation closes the active chunk. A sealed chunk keeps its
capture-time identity and is never rebound to later state. The engine uses
`userId ?? anonymousId` as the replay subject and retains the complete witness
for readback.

Masking and blocking happen before payload serialization. The official SDK's
sealed encoder and public API do not let application code override
`appliedBeforeSerialization`, `secureInputsMasked`, or the audit hashes.
However, every field sent with a public routing site key is still an
unauthenticated client assertion. The booleans and hashes are audit receipts,
not independent proof of privacy. Privacy evidence requires external readback
to prove that a planted secret is absent from the accepted bytes, stored
payload, and rendered replay. `maskingProfileHash` is the lowercase SHA-256 of
the canonical closed masking profile used for compatibility comparison;
`effectivePolicyHash` remains a capture-time audit receipt.

`payload` is base64 of the exact inner bytes after the declared compression is
applied once. HTTP content encoding remains identity. The complete UTF-8
request, including base64 and wrapper fields, is governed by
`limits.replayChunkBytes`.

The public `elu-browser-dom-v1` fixture is a canonical UTF-8 JSON array with
two ordered logical records: a metadata record followed by a full DOM
snapshot record. Its only text node contains `[masked]`. The array is gzip
compressed exactly once and then base64 encoded into the replay fixture. The
compressed and decompressed bytes, lengths, and SHA-256 digests are pinned in
`test-vectors/replay-activity.json`. This minimum fixture proves a non-empty,
masked codec payload and deterministic transport bytes; live playback remains
pending external verification.

Payload admission is bounded and ordered. Base64 must use the canonical RFC
4648 standard alphabet and required padding. Gzip is streamed with a hard
decoded ceiling of 16,777,216 bytes, exactly one member, no trailing member or
data, and no nested compressed payload. Decoding aborts before it can produce a
byte beyond the ceiling. The decoded codec document is strict canonical JSON,
has an array at the top level, contains at most 10,000 logical records, and has
at most 256 levels of JSON nesting. Invalid gzip, malformed or noncanonical
base64, excess decoded bytes or records, excess nesting, duplicate keys, and
unpaired Unicode surrogates are permanent rejections and never receive an
acknowledgement.

## Canonical request identity

Replay v2 reuses `elu-canonical-json-v1` exactly, including its byte-pinned v1
test vector. Object keys are recursively sorted by UTF-16 code-unit order.
Arrays retain input order. Any number token that mathematically denotes an
integer in the signed 64-bit range is rendered as that exact minimal integer,
including decimal and exponent spellings such as `9007199254740993.0` and
`9007199254740993e0`. Every other number is parsed as finite binary64 and
rendered with `JSON.stringify`; for example, `9223372036854775808` becomes
`9223372036854776000`. Non-finite binary64 values are rejected. Strings use
JSON escaping without Unicode normalization; unpaired Unicode surrogates are
rejected. The vectors also freeze control escaping, BMP/astral ordering,
composed/decomposed strings, safe and signed-64-bit boundaries, and literal
versus escaped-equivalent duplicate keys. Replay integer fields remain limited
to JavaScript's safe integer range.

```text
canonicalChunk = strict canonical UTF-8 JSON of the complete chunk

material = UTF8("elu-sdk-replay-request-v2")
           || 0x00
           || uint32be(byteLength(canonicalChunk))
           || canonicalChunk

requestId = "request_" || lowercaseHex(SHA256(material))
```

The complete request is then encoded with the same recursive lexicographic-key
canonicalizer. Its top-level key order is therefore `chunk`, `requestId`,
`schemaVersion`. No send or retry timestamp is added. Retries reuse the exact
request bytes, digest, request ID, replay ID, chunk ID, and sequence.

Raw replay requests must be scanned before ordinary JSON parsing. An object
that repeats a decoded key is invalid, whether the duplicate is written
literally or through a JSON escape such as `request\u0049d` for `requestId`.
The same rule applies recursively inside `chunk`.

## Ordering, acknowledgement, and retention

- Chunks are ordered independently within each replay by sequence.
- Scheduling first selects the lowest unresolved eligible sequence per replay,
  then the smallest durable global ordinal across those replay heads.
- Acknowledgement deletes a row only after request ID, replay ID, chunk ID,
  sequence, and `accepted` result all match.
- A schema-valid one-chunk `413` is terminal for that exact row.
- Unchanged `429`, `5xx`, and network retries preserve the exact request.
- `401` and `403` permanently block the unchanged request.
- Client age is seven days from `startedAt`; idempotency resolution is retained
  by the engine for at least eight days after first resolution.
- Replay identity is scoped to the stable authorized site ID. Request
  uniqueness is `(siteId, requestId)` bound to the complete canonical request
  digest. Semantic uniqueness is `(siteId, replayId, chunkId)`. Ordering
  uniqueness is `(siteId, replayId, sequence)`.
- An exact duplicate across all three scopes and the body digest returns the
  same stored effective acknowledgement. A request, semantic, or ordering scope
  that collides with different bound identity or bytes is a permanent `409`
  `replay-identity-conflict`; it publishes nothing and returns the closed
  replay error body, never an acknowledgement.
- A conflict response binds the submitted request ID but does not expose the
  prior winner. The engine must never reuse an old acknowledgement for a new
  request ID or echo a newly constructed acknowledgement for rejected bytes.

## Aggregate queue authority

Event, mutation, and replay work share one per-site durable budget:

```text
aggregateCount = eventMutationCount + replayCount
aggregateBytes = eventMutationBytes + replayRequestBytes
effectiveQueueByteLimit = min(config.queueBytes, 268435456)
effectiveReplayRequestLimit = min(config.replayChunkBytes, 5242880)
```

Admission, restriction, retry, and acknowledgement rules are frozen by
`test-vectors/replay-activity.json`. End-to-end acceptance, attribution,
privacy evidence, and playback are frozen by `readback-expectations.json`.

Run `node --test conformance/v2/contract.test.mjs` to validate the manifest,
raw-byte checksums, schemas, fixtures, semantic rules, and operational vector
without installing dependencies.
