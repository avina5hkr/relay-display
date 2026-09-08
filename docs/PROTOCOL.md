# RelayDisplay wire protocol

Version 1.0. One persistent TCP connection carries everything.

## Framing

Fixed 20-byte header, big-endian throughout, then the payload.

```
offset size field
  0     4   magic          0x524C5931 ("RLY1")
  4     1   versionMajor   must be 1
  5     1   versionMinor   informational; a higher value is accepted
  6     1   flags          bit0 = ENCRYPTED, all other bits must be 0
  7     1   reserved       must be 0
  8     8   sequence       per-direction record counter, starts at 0
 16     4   payloadLength  0 .. 262144
 20   ...   payload
```

`FrameCodec.parseHeader` is the only place a peer-supplied length is read, and it validates the
length **before** any buffer is allocated for it. A negative or oversized value is rejected as
`PAYLOAD_TOO_LARGE`.

For an encrypted frame the payload is `AES-256-GCM(ciphertext || tag)` and the 20 header bytes
are the AAD, which binds the sequence number, version and flags into the authentication tag.

## Records

Inside a frame (after decryption, once the session is secure):

```
offset size field
  0     2   messageType
  2    16   messageId (UUID)
 18   ...   TLV body
```

Every message carries an id. Acknowledgements reference it, and a command replayed after a
reconnect is recognised as a duplicate rather than applied twice.

## TLV body encoding

```
tag:u8 | length:u32be | value[length]
```

Tags are written in strictly ascending order and never repeat. A decoder that sees an
out-of-order or repeated tag rejects the entire body. That makes one logical message have exactly
one valid byte encoding — which matters because the handshake transcript hashes encoded bytes,
and because it makes golden tests meaningful.

Maximum 64 fields per body.

## Limits

Checked before allocation, in `ProtocolConstants` and `ContentLimits`.

| Limit | Value | Note |
| --- | --- | --- |
| Frame payload | 256 KiB | absolute ceiling for any single frame |
| Control record | 256 KiB | typically far smaller |
| Text / URL / QR payload | 64 KiB | |
| File transfer | 50 MiB | |
| Transfer chunk | 64 KiB | |
| Filename (sanitized) | 255 bytes | display only; never used to build a path |
| MIME string | 128 bytes | |
| Device name | 64 bytes | |
| Mirror frame | 192 KiB | |
| Concurrent bulk transfers | 1 | a second offer is rejected with `BUSY` |

## Timings

| Setting | Value |
| --- | --- |
| Heartbeat interval | 15 s |
| Dead-peer threshold | 45 s |
| Connect timeout | 5 s (fast path 1.5 s) |
| Handshake timeout | 10 s |
| Discovery resolve timeout | 8 s |
| Transfer inactivity timeout | 30 s |
| Pairing token lifetime | 120 s |
| Reconnect ladder | 1, 2, 4, 8, 15, 30 s, ±20 % jitter |

## Message types

Codes are permanent. New messages append; existing ones never renumber. An unknown code is
rejected with `UNKNOWN_MESSAGE_TYPE` rather than ignored, so a downgrade is visible.

| Code | Name | Secure? |
| --- | --- | --- |
| 0x0001 | HELLO | no |
| 0x0002 | HELLO_ACK | no |
| 0x0003 | AUTH_CONFIRM | no |
| 0x0004 | AUTH_RESULT | no |
| 0x0010 | SAS_CONFIRM | yes |
| 0x0011 / 0x0012 | PING / PONG | yes |
| 0x0013 | ACK | yes |
| 0x0014 | ERROR | yes |
| 0x0015 | BYE | yes |
| 0x0030 | SHOW_TEXT | yes |
| 0x0031 | SHOW_QR | yes |
| 0x0032 | SHOW_LINK | yes |
| 0x0033 | PRESENT_COMMAND | yes |
| 0x0034 | PRESENTATION_STATE | yes |
| 0x0035 | PRESENTATION_DISMISS | yes |
| 0x0036 | PRESENTATION_SYNC_REQUEST | yes |
| 0x0040–0x0042 | CONTENT_OFFER / ACCEPT / REJECT | yes |
| 0x0043–0x0046 | TRANSFER_START / CHUNK / COMPLETE / CANCEL | yes |
| 0x0047 | SHOW_FILE | yes |
| 0x0048 | PDF_PAGE_COMMAND | yes |
| 0x0049 | FILE_BATCH_OFFER | yes |
| 0x004A | FILE_BATCH_ACCEPT | yes |
| 0x004B | FILE_BATCH_REJECT | yes |
| 0x004C | FILE_BATCH_CANCEL | yes |
| 0x0050–0x0054 | MIRROR_START / CONFIG / FRAME / STOP / KEYFRAME_REQUEST | yes |

"Secure" means the message is refused if it arrives before the session is encrypted. That check
lives in `SecureConnection.read`, so it cannot be forgotten at a call site.

## Handshake

```
Controller (initiator)                      Display (responder)
  |-- HELLO ------------------------------------->|
  |    eph pub, identity pub, nonce, caps,        |
  |    needs-SAS                                  |
  |<------------------------------------ HELLO_ACK|
  |    eph pub, identity pub, nonce, caps,        |
  |    needs-SAS                                  |
  |-- AUTH_CONFIRM ------------------------------>|
  |    ECDSA(transcript), [HMAC(token,transcript)]|
  |<---------------------------------- AUTH_RESULT|
  |    accepted + ECDSA(transcript)               |
  |=========== encrypted from here ===============|
  |-- SAS_CONFIRM ------------------------------->|   (only when needed)
  |<---------------------------------- SAS_CONFIRM|
```

- `transcript = SHA-256("RLY1-transcript" || encode(HELLO) || encode(HELLO_ACK))`
- `prk = HKDF-Extract(salt = transcript, ikm = ECDH-P256(eph_a, eph_b))`
- `c2d key = HKDF-Expand(prk, "RLY1 c2d key", 32)`, `d2c key` likewise
- `c2d iv = HKDF-Expand(prk, "RLY1 c2d iv", 4)`, `d2c iv` likewise
- `SAS = HKDF-Expand(prk, "RLY1 sas", 4) mod 1e6`, printed as six digits
- Nonce = 4-byte directional prefix || 8-byte big-endian sequence

Signature contexts are distinct per direction (`RLY1-auth-initiator` / `RLY1-auth-responder`), so
a signature cannot be reflected back at its sender.

`needs-SAS` is OR-ed from both HELLOs. A device that cannot authenticate the peer from stored
trust or a pairing token can never be talked out of asking the user.

## Error codes

`ProtocolErrorCode`: `BAD_MAGIC`, `UNSUPPORTED_VERSION`, `MALFORMED_FRAME`, `PAYLOAD_TOO_LARGE`,
`UNKNOWN_MESSAGE_TYPE`, `NOT_AUTHENTICATED`, `AUTH_FAILED`, `REPLAY_DETECTED`, `DECRYPT_FAILED`,
`UNSUPPORTED_FORMAT`, `TOO_LARGE`, `INSUFFICIENT_STORAGE`, `DECODE_FAILED`, `PERMISSION_DENIED`,
`TIMEOUT`, `BUSY`, `CANCELLED`, `RATE_LIMITED`, `INTERNAL`.

## File transfer

```
CONTENT_OFFER   id, kind, size, mime, display name, sha256
CONTENT_ACCEPT  or CONTENT_REJECT(code)
TRANSFER_START  total bytes, chunk size
TRANSFER_CHUNK  index (strictly sequential), data
TRANSFER_COMPLETE  sha256
ACK             ok / error
SHOW_FILE       kind, fit mode
```

Receiver rules, all enforced in `TransferReceiver`:

1. Size, MIME and free space are checked at offer time; nothing is opened before acceptance.
2. Chunks stream to `<cache>/incoming/<transferId>.part`. The whole file is never in memory.
3. Chunk indices must be strictly sequential. Out-of-order aborts the transfer.
4. On completion the byte count **and** the SHA-256 must both match, and the sender's own
   completion digest must equal the digest it offered.
5. The file's leading bytes are sniffed and must match the declared kind, so a decoder is never
   pointed at something that is not what it claims to be.
6. Only then is the file atomically renamed into `<cache>/ready/<transferId>.<ext>`.
7. Any failure, cancel or disconnect deletes the partial file.

The final name comes from the transfer UUID. The peer's display name is sanitized and used only
as a label.

## Generic file transfer (capability `file-v2`)

Added alongside the existing image and PDF transfer, reusing the same
`CONTENT_OFFER / TRANSFER_START / TRANSFER_CHUNK / TRANSFER_COMPLETE` sequence rather than
introducing a second mechanism. Only three things are new.

**`ContentKind.FILE` = wire code 3.** Permanent, like the other codes. Unlike `IMAGE` and `PDF`
the receiver never decodes these bytes, so any MIME type is accepted and content sniffing is not
applied — sniffing cannot protect something nothing parses, and would reject most ordinary files
whose leading bytes this app does not recognise.

**Version lives in the capability string, and this build speaks only `file-v2`.** A display
announces `file-v2` in its HELLO capabilities. A controller checks for it before offering a batch
and, if it is absent, tells the user that file transfer needs a newer Relay Display on the other
phone. Nothing is sent.

`file-v1` is **not** advertised alongside it. v1 was not merely older, it was unsafe: its manifest
carried no transfer id and no digest, and the per-file `CONTENT_OFFER` carried no batch identity,
so an accepted batch could be followed by entirely different files. Announcing both versions would
mean either honouring those unbound offers -- the vulnerability -- or advertising a capability this
build refuses to act on. A peer announcing only `file-v1` therefore takes the same path as an
unversioned peer: a clear "needs a newer version" refusal before anything is sent. No release ever
shipped v1, so nothing in the field is broken by this. `FileCapabilityTest` pins the negotiation,
including a future peer that advertises both (it is spoken to as v2).

Image and PDF presentation transfers are unaffected: they are gated on the `image` and `pdf`
capabilities and work against any peer, including one too old for generic files.

**Zero-byte files are accepted for `FILE` only.** An empty generic file is ordinary; an empty
image or PDF is not, because there is nothing for a decoder to open. The offer gate is therefore
kind-aware rather than uniformly permissive.

### The batch exchange

A batch wraps the per-file exchange rather than replacing it. One confirmation covers the whole
selection; each file then streams through the `CONTENT_OFFER / TRANSFER_*` machinery that already
existed and was already tested.

```
Controller                                       Display
    | FILE_BATCH_OFFER  batchId, manifest[]
    |---------------------------------------------->|   (user is asked)
    |                                               |
    |        FILE_BATCH_ACCEPT batchId              |
    |<----------------------------------------------|
    |                                               |
    |   ... per file, sequentially, in manifest order:
    |   CONTENT_OFFER(batchId, transferId, index) /
    |   TRANSFER_START / TRANSFER_CHUNK* /
    |   TRANSFER_COMPLETE / SHOW_FILE(kind=FILE)    |
    |---------------------------------------------->|
```

In place of the accept, either `FILE_BATCH_REJECT batchId, errorCode` (the user said no, or a limit
was broken) or nothing, in which case the sender's decision timeout fires. Either side may send
`FILE_BATCH_CANCEL batchId, errorCode` at any point to end the batch.

### Consent is bound to specific files

**Every manifest entry carries a pre-allocated `transferId` and the SHA-256 of the exact bytes that
will be sent**, and every generic `CONTENT_OFFER` carries the `batchId` and the manifest index. The
receiver keeps the accepted manifest immutable, keyed by transfer id, and matches each offer
against it *before* creating a partial file. An offer is refused unless all of the following hold:

| Check | Refusal code |
| --- | --- |
| a batch is accepted and not yet terminal | `PERMISSION_DENIED` |
| the offer names a batch at all | `PERMISSION_DENIED` |
| the `batchId` matches the accepted one | `PERMISSION_DENIED` |
| the `transferId` exists in the accepted manifest | `PERMISSION_DENIED` |
| that entry has not already started or finished | `BUSY` / `PERMISSION_DENIED` |
| the manifest index agrees | `MALFORMED_FRAME` |
| the size matches exactly | `MALFORMED_FRAME` |
| the SHA-256 matches | `MALFORMED_FRAME` |
| the MIME type matches, normalised | `UNSUPPORTED_FORMAT` |
| the display name matches, normalised | `MALFORMED_FRAME` |

Because each entry can be consumed exactly once and an unknown transfer id is refused, no unlisted
file can be accepted and no extra file can appear beyond the accepted count -- the count is a
consequence of the manifest, not a separate counter that a peer could avoid advancing.

**Why this is a version change rather than stricter checks.** In `file-v1` there was nothing to
check *against*: the manifest identified no transfer and pinned no content, and the offer named no
batch. The receiver's only gate was a boolean saying some batch had been accepted, so a controller
could display one manifest and send different files, and a peer that never sent the closing
`SHOW_FILE` left consent alive for the life of the session. Pairing authenticates a peer; it does
not make everything that peer later says true.

**One normalization policy, applied to both sides.** Names are compared after passing through the
app's own `FilenameSanitizer` at the wire byte limit, so the compared form is exactly the form that
would be written to disk and shown to the user. MIME types are compared trimmed and lowercased,
with blank meaning the fallback. Comparing raw wire strings would let a name that sanitises to the
approved one read as a different file; comparing unsanitised names would compare values the user
was never shown.

**Manifest entry encoding** (nested inside one TLV field, because the TLV layer keys fields by tag
and so has no repeated fields):

```
per entry:  transferId:16 | u16 nameLen | name (UTF-8) | u16 mimeLen | mime (UTF-8)
            | i64 sizeBytes | sha256:32
```

The decoder bounds the whole field before parsing, requires the entry count to match the `u8` count
in the header, refuses a manifest that names the same transfer id twice (an entry the receiver
cannot tell apart from another is exactly what the binding prevents), rejects any length exceeding
the remaining buffer or its own field limit, and treats trailing bytes as `MALFORMED_FRAME`.

**No sender name on the wire.** `file-v1`'s `FILE_BATCH_OFFER` carried a `senderName` string and
the consent dialog displayed it, which meant the identity the user was shown came from inside a
message the peer composed. The field is gone; the dialog uses the authenticated peer name from the
handshake. Tag 2 is left unused rather than recycled.

### Batch state machine

One terminal state per batch, on each side, and cleanup that is safe to run repeatedly.

**Receiving side.** `offered` (awaiting consent, with a local expiry) then either `accepted`
(transfers may run) or straight to a terminal state. Terminal: `completed`, `rejected`,
`cancelled`, `expired`, `disconnected`. Each manifest entry additionally moves through
`pending -> receiving -> received | failed | cancelled`, and the batch is finished when no entry is
pending or receiving.

**Sending side.** `preparing` (spooling) then `awaiting consent` then `transferring`, and terminal
as `completed`, `rejected`, `cancelled`, `timed out` or `disconnected`. Per file:
`waiting -> accepted -> sending -> verifying -> complete | rejected | cancelled | failed`.

Rules the implementation holds to:

- **Cancellation notifies the peer first, then cancels the coroutine.** This is the fix for a real
  bug: cancelling used to call `batchJob.cancel()` and nothing else, and because `streamOneFile`
  rethrows `CancellationException` before it can send anything, the Display was never told. It kept
  its consent, its open partial and its "transfer running" state, and refused every later batch as
  `BUSY` until the session ended. `FILE_BATCH_CANCEL` goes out on the still-live session, together
  with a `TRANSFER_CANCEL` for the individual transfer if one is mid-flight, and only then is the
  coroutine cancelled.
- **The sender's consent timeout tells the Display**, so a dialog still on screen there is
  dismissed rather than outliving the sender's patience.
- **The receiver also expires an unanswered offer locally** after `CONSENT_EXPIRY_MS`, which is
  longer than the sender's decision timeout. That is the backstop for a lost cancel or a
  force-stopped controller; without it a prompt that can never be answered would block every later
  batch.
- **Late or unknown accept/reject/cancel messages are ignored**, not treated as errors: a cancel
  naming a batch that already ended is what a crossing cancel looks like.
- **Every terminal path clears** the accepted manifest, the pending offer and its expiry job, the
  dialog state, the inbound receiver and its `.part` file, the outbound spool files, and every
  pending deferred decision and acknowledgement.
- **Files already received and hash-verified are kept.** Those bytes passed every check and the
  user was told they arrived. Consent still expires, which is the part that matters for safety.
- **After a cancellation or failure a new batch can be sent immediately**, with no reconnect.

### Queue ordering is part of the transfer contract### Queue ordering is part of the transfer contract

`TRANSFER_CHUNK` and `TRANSFER_COMPLETE` travel on the **same** queue (`BULK`), and that is a
requirement rather than an implementation detail. The session writer is strict priority: anything
on `CONTROL` is written before anything on `BULK`. A completion on `CONTROL` therefore overtakes
chunks still queued, and the receiver — which requires chunks in order and checks the byte count
before the digest — rejects a file that was never corrupt.

Observed on hardware: a 19.9 MB file arrived as exactly 301 of 304 chunks. Small transfers never
show it, because `BULK` has no backlog to jump.

`TRANSFER_START` is safe on `CONTROL`: overtaking can only make it arrive earlier, and it must
precede its chunks anyway. `TRANSFER_CANCEL` stays on `CONTROL` deliberately — cancelling is meant
to overtake the backlog it is abandoning.

### Sizes are measured, not declared

**No "unknown size" sentinel travels on the wire.** A `ContentResolver` is not obliged to report a
size, so the sender resolves it before offering: a single bounded preparation pass streams the
source once, counting bytes and computing the SHA-256 the offer has to carry anyway. The size in
`CONTENT_OFFER` and in a batch manifest is therefore always the real length of the bytes that will
arrive.

This is deliberate, and it replaced a contradiction. The picker used to refuse anything whose size
was not reported (`size <= 0`, which also refused legitimately empty files) while this document
claimed `-1` was supported and the receiver rejected every negative size. Measuring settles it
without a policy argument and costs nothing, because the digest pass already had to read the whole
stream. Consequences:

- a receiver can always show a real total and check it against its own limits before asking;
- "the file changed while sending" can no longer be caused by a merely inaccurate provider;
- a source that yields different bytes on the second read fails the receiver's digest check, which
  is the correct outcome and is what that check is for;
- any negative size on the wire is `MALFORMED_FRAME`.

**Zero is a real size.** An empty generic file transfers normally. An empty image or PDF is still
refused, because there is nothing for a decoder to open.

### Limits

Enforced by `FileTransferPolicy` before anything is allocated, opened or written.

| Limit | Value |
| --- | ---: |
| Files per batch | 20 |
| Single file | 50 MiB (shared with image/PDF) |
| Batch total | 200 MiB |
| Receiver retained bytes | 400 MiB |
| Receiver retained files | 60 |
| Chunk | 64 KiB (existing `CHUNK_BYTES`) |
| Filename | 255 bytes **and** 120 characters, whole code points only, extension preserved |
| MIME string | 128 bytes, no control characters |
| Retention expiry | 24 h |
| Unanswered consent prompt | 150 s (receiver-side expiry) |
| Sender's decision timeout | 120 s |

**The retention budget must be able to hold one whole legal batch.** `ContentCache` takes its byte
and entry budgets from `FileTransferPolicy` rather than restating them, and
`FileTransferPolicy.retentionInvariantsHold()` pins the relationship:
`MAX_PENDING_BYTES >= MAX_BATCH_BYTES` and `MAX_PENDING_FILES >= MAX_FILES_PER_BATCH`. The cache
previously kept its own copy of these numbers, and its entry budget (12) was smaller than one
legal batch (20 files), so accepting a full batch silently deleted the first eight files of it.
Nothing failed; the files were simply gone.

Retention is enforced in three places, all of them real: oldest-first eviction on every promote,
a partial sweep at startup, and an expiry sweep on the same startup pass. There is no advertised
limit here that nothing implements.

**Totals are accumulated, not summed.** `validateBatch` adds sizes one at a time with an early
exit, and the receiver's free-space check subtracts rather than adds, so no attacker-influenced
value can wrap a total negative and pass a limit comparison.

## Versioning and compatibility

- A different `versionMajor` is rejected at the frame layer.
- A higher `versionMinor` is accepted; the session runs at `min(local, peer)`.
- Capabilities travel as a sorted comma-separated set in HELLO. Unknown tokens are ignored.

## Presentation identity and reconciliation

Added after real-device testing showed the two phones disagreeing about what was on screen.

### Session identity

Both peers derive the same `sessionId` from the handshake key schedule:

```
sessionId = HKDF-Expand(prk, "RLY1 session id", 16)   rendered as 32 hex characters
```

No extra round trip, and a third party that did not complete the handshake cannot guess it.
Every presentation message carries it, and a message whose session id is not the current one is
dropped without touching state.

### Presentation envelope

`SHOW_TEXT`, `SHOW_QR`, `SHOW_LINK` and `SHOW_FILE` carry two optional TLV fields:

| Tag | Field | Type |
| --- | --- | --- |
| 20 | presentationId | 16 bytes (UUID) |
| 21 | revision | i64, non-negative |

Both or neither. A half-present envelope is `MALFORMED_FRAME` rather than something to guess at.
Absent means the sender does not track presentations, and the receiver assigns identity locally.

### Revisions are a Lamport clock

Both devices can originate a presentation change: the Controller by sending content, the Display
by closing it. Rather than making one side the sole allocator, each keeps a counter and, on
receiving revision `R`, sets `next = max(next, R + 1)`.

That gives a strict total order with no coordination, and it is why a Display closing content
always outranks the content it closed, even though the Controller issued that content's revision.

### PRESENTATION_STATE (Display to Controller)

| Tag | Field |
| --- | --- |
| 1 | sessionId |
| 2 | presentationId |
| 3 | revision |
| 4 | phase (`PresentationPhase.wireCode`) |
| 5 | transferId (optional) |

Phases: 0 NoPresentation, 1 PreparingContent, 2 Transferring, 3 ShowingText, 4 ShowingQr,
5 ShowingLink, 6 ShowingImage, 7 ShowingPdf, 8 Mirroring, 9 Closing, 10 PresentationError.

Carries no content: a peer learns *that* text is showing, never what it says. Sent after every
content change, every dismissal, every presentation command, and on request.

### PRESENTATION_DISMISS (either direction)

`sessionId`, `presentationId`, `revision`. Idempotent: dismissing an already-dismissed
presentation changes nothing but still produces a `PRESENTATION_STATE` reply, which is what makes
a retry after a lost acknowledgement safe.

### PRESENTATION_SYNC_REQUEST

Sent by the Controller as soon as a session is established, and after any reconnect. The Display
answers with `PRESENTATION_STATE`.

### Reconciliation policy

One rule, chosen for safety and applied without exception:

**The Display is authoritative about the Display.**

- If the Display reports no presentation, the Controller adopts that immediately.
- Stale Controller belief never resurrects content.
- Mirroring is never restarted from reconciliation. A lost projection always requires a fresh
  explicit user action and fresh system consent.

### Rejection rules

An incoming presentation message is dropped when:

1. its `sessionId` differs from the current session, or
2. its `revision` is less than or equal to the revision already in force.

Both live in `PresentationSnapshot.shouldAccept`, so there is exactly one implementation.
