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

## Generic file transfer (capability `file-v1`)

Added alongside the existing image and PDF transfer, reusing the same
`CONTENT_OFFER / TRANSFER_START / TRANSFER_CHUNK / TRANSFER_COMPLETE` sequence rather than
introducing a second mechanism. Only three things are new.

**`ContentKind.FILE` = wire code 3.** Permanent, like the other codes. Unlike `IMAGE` and `PDF`
the receiver never decodes these bytes, so any MIME type is accepted and content sniffing is not
applied — sniffing cannot protect something nothing parses, and would reject most ordinary files
whose leading bytes this app does not recognise.

**Version lives in the capability string.** A display announces `file-v1` in its HELLO
capabilities. A controller checks for it before offering a file batch and, if it is absent, tells
the user that file transfer needs a newer Relay Display on the other phone. Nothing is sent. A
future incompatible format announces `file-v2`, which an older peer simply will not match, so an
old build keeps failing cleanly rather than half-parsing a newer wire format.

**Zero-byte files are accepted for `FILE` only.** An empty generic file is ordinary; an empty
image or PDF is not, because there is nothing for a decoder to open. The offer gate is therefore
kind-aware rather than uniformly permissive.

### Limits

Enforced by `FileTransferPolicy` before anything is allocated, opened or written.

| Limit | Value |
| --- | ---: |
| Files per batch | 20 |
| Single file | 50 MiB (shared with image/PDF) |
| Batch total, known sizes | 200 MiB |
| Receiver pending bytes | 400 MiB |
| Receiver pending files | 60 |
| Chunk | 64 KiB (existing `CHUNK_BYTES`) |
| Filename | 255 bytes, truncated by **bytes**, whole code points only |
| MIME string | 128 bytes, no control characters |
| Pending-file expiry | 24 h |
| Inactivity timeout | 30 s |

A declared size of `-1` means "unknown", which a content provider is entitled to report. Any
other negative value is `MALFORMED_FRAME`. Unknown sizes do not contribute to the batch total and
are bounded per-file while streaming instead.

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
