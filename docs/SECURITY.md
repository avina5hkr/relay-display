# RelayDisplay security design and threat model

## Assets

1. **Relayed content** — text, links, QR payloads, images, PDF pages, screen video.
2. **The pairing relationship** — which display this controller will talk to.
3. **The device identity key** — an EC P-256 private key in the Android Keystore.
4. **Session keys** — ephemeral, in memory only, wiped when the session ends.

## Trust boundaries

- **The local network is hostile.** Home Wi-Fi, a café network and a phone hotspot are all
  assumed to contain devices that will connect, probe and lie.
- **The peer is trusted only after pairing**, and only for as long as its identity key matches
  the pinned fingerprint.
- **Incoming intents are untrusted.** `MainActivity` is the only exported component.
- **Everything a peer sends is untrusted input** until it has been length-checked, decrypted,
  authenticated and (for files) digest- and content-verified.

## What protects what

### Identity

Each installation generates one EC P-256 key pair inside the Android Keystore on first use. The
private key never leaves the keystore.

There is deliberately **no software fallback**. If the keystore is unusable, pairing is disabled
and the UI says so. Writing a private key to app storage would quietly downgrade the guarantee
the whole model rests on, and a user cannot be expected to notice that.

The fingerprint is `SHA-256(X.509 SubjectPublicKeyInfo)`, displayed as four groups of four hex
digits.

### Authenticated key exchange

Ephemeral ECDH on P-256 (not X25519: `XDH` needs API 33 and the companion phone is older), with
the shared secret fed through HKDF-SHA-256 salted with a transcript hash that covers both HELLO
messages — and therefore both ephemeral keys, both identity keys, both nonces, both capability
sets and both "needs SAS" flags.

Each side signs the transcript with its identity key, under a direction-specific context string,
so a signature cannot be reflected.

**Machine-in-the-middle:** an attacker relaying between the two phones must run two separate
handshakes, so the two transcripts differ. It cannot produce a valid signature over either
transcript without the corresponding identity private key. If it substitutes its own identity
key, then either the pinned fingerprint check fails outright, or the two devices compute
different short authentication strings and the user sees the mismatch. `HandshakeTest` covers
this case end to end.

### Authenticating the peer

In order of preference:

1. **Pinned fingerprint** from a previous pairing. A mismatch aborts, and is never auto-retried.
2. **QR pairing token** — 128 random bits, single use, 120-second lifetime. The controller proves
   knowledge with `HMAC-SHA256(token, "RLY1-token-proof" || transcript)`. Because the proof is
   bound to a transcript, a captured proof is useless in any other session.
3. **Six-digit short authentication string** derived from the same key material, compared by the
   user on both screens. Both sides must confirm before trust is written.

The `needs-SAS` flag is OR-ed from both sides, so a device that has forgotten its peer forces
verification on for both. A responder holding a pending token that receives an *unproven*
AUTH_CONFIRM rejects it rather than silently falling back to unauthenticated — that fallback
would be exactly the downgrade this design exists to prevent.

### Record protection

AES-256-GCM. Independent keys and nonce prefixes per direction, so a reflected record can never
authenticate. Nonce = 4-byte directional prefix || 8-byte sequence, and the sequence is also in
the frame header, which is the AAD.

- **Replay:** the receiver requires exactly the next sequence number. `REPLAY_DETECTED`.
- **Reordering:** same check.
- **Renumbering:** the sequence is in the nonce *and* the AAD, so relabelling a record breaks the
  tag. Covered by `SecureChannelTest`.
- **Nonce reuse across reconnects:** every session derives fresh keys from fresh ephemeral keys,
  so sequence 0 in a new session uses a different key. Sequence exhaustion throws rather than
  wrapping.

## Attack surface review

| Attack | Mitigation |
| --- | --- |
| Unauthenticated command | `SecureConnection.read` refuses any message marked `requiresSecureSession` before the session is secure. Tested. |
| Stripping the encryption flag | A plaintext frame after activation is `NOT_AUTHENTICATED`. Tested. |
| Peer-ID / fingerprint substitution | Pinned fingerprint compared in constant time; mismatch aborts. Tested both directions. |
| MITM during first pairing | Distinct transcripts → different SAS → user aborts. Tested. |
| Downgrade to no verification | `needs-SAS` is OR-ed; a pending token with no valid proof is rejected. Tested. |
| Pairing brute force | 128-bit single-use token, 120 s lifetime, and failed authentications are rate-limited per remote address with exponential penalty (2 s doubling to 60 s after 3 failures). |
| Token reuse | Burned on use; proof is transcript-bound. Tested. |
| Unbounded length field | Every length is range-checked before allocation, at the frame, TLV and message layers. Tested with `0xFFFFFFFF`. |
| Integer overflow in a length | TLV lengths above `Int.MAX_VALUE` are rejected while still held as a `Long`. |
| Bitmap bomb | Bounds are read first; declared dimensions above 20 000 px are refused; sample size is chosen before allocation. |
| Oversized QR / text | 64 KiB protocol cap, and the QR encoder refuses above 1 200 bytes because a denser code would not scan. |
| Path traversal | The stored filename is the transfer UUID. The peer's name is sanitized (last path segment, forbidden characters replaced, reserved device names defused) and used only as a label. Tested. |
| Malicious deep link | Only `https` and `http` are ever offered. `file:`, `content:`, `intent:`, `javascript:`, `data:`, `market:` and every unknown scheme are refused on both the send and receive paths. A received link is never opened automatically. Tested. |
| Misleading link display | The display shows the host with userinfo stripped, not the full URL. Tested. |
| PendingIntent hijack | Every `PendingIntent` is `FLAG_IMMUTABLE` and carries no fillable extras. |
| Exported component abuse | Only `MainActivity` is exported. Both services are `exported="false"`. Share intents are bounded and validated. |
| Transfer flood | One concurrent transfer; a second offer gets `BUSY`. The cache is bounded by bytes and entries with oldest-first eviction. |
| Slow-reader DoS | Bounded outbound queue plus a bounded pipe means backpressure, not heap growth. Tested. |
| Repeated connection attempts | One authoritative session; a second connection is closed. Rate limiting as above. |
| Android 17 LAN denial | `PermissionPolicy` requests `ACCESS_LOCAL_NETWORK` only on API 37+, and a denial produces a blocked state with an actionable button rather than a retry loop. Tested at API 23/33/34/36/37. |
| MediaProjection misuse | Consent is requested per session and passed straight to the service; nothing is cached. The FGS starts before `getMediaProjection`. `MediaProjection.Callback.onStop` tears everything down. |

## Logging and diagnostics

The diagnostics log holds at most 300 entries of at most 200 characters, in memory only, and is
never written to disk.

Never logged: keys, tokens, session material, text content, QR payloads, URLs, filenames, file
bytes, or full addresses. Addresses are logged as `192.168.1.x`. `PairingPayload.toString` and
`PairingOffer.toString` deliberately omit the token and the fingerprint, and that is unit tested.

## Storage

Persisted: role, mode, device name, device id, presentation preferences, and one trusted-peer
record (peer id, name, fingerprint, public key, capabilities, last endpoint, last subnet).

Never persisted: private keys outside the Keystore, session keys, pairing tokens, or received
content beyond the bounded cache.

`android:allowBackup="false"`. The identity key is not exportable, so restoring preferences onto
a different phone would leave a pinned fingerprint whose private half no longer exists — the app
would look paired and fail every handshake. The backup rule files document this in case backup is
ever re-enabled.

## Deliberate non-goals

- **No AccessibilityService, Device Admin, root, ADB, hidden APIs or input injection.** "Control"
  means RelayDisplay's own window and nothing else.
- **No permissive trust manager.** There is no TLS here at all; the custom channel is pinned to
  the peer identity by construction.
- **No audio capture.**
- **No bypass of `FLAG_SECURE`.** Protected content appears black during mirroring, by Android's
  design, and the UI says so.

## Residual risks

1. **Discovery reveals that RelayDisplay is running.** The mDNS advertisement carries only the
   protocol version and the user's chosen device name — no identity, no fingerprint, no address
   history. A stable identifier was deliberately left out because it would be a cross-network
   tracking token; the cost is that the controller may have to try more than one responder.
2. **Dialling a discovered service reveals the controller's identity public key and device name**
   to whatever answers, before the fingerprint check can fail. Bounded because discovery only
   runs when the user asks for it and only targets `_relaydisplay._tcp.` responders, but it is a
   real leak on a hostile network. Scanning the display's QR code avoids it entirely.
3. **A user who confirms a mismatched six-digit code defeats the MITM protection.** The wording
   on the pairing screen is explicit about this; nothing else can be done from software.
4. **No forward secrecy across a message, only across a session.** A device compromised while a
   session is live exposes that session's content. Keys are wiped at session end.
5. **The keystore is trusted.** On a device with a broken or compromised keystore the identity
   guarantee is only as good as that keystore.
6. **Rate limiting is per remote address** and is therefore weaker against an attacker who can
   change source address freely on the same LAN.

## Review checklist status

Every item in the specification's section 19 was reviewed. The table above records the mitigation
and whether it has an automated test. Items with no automated test — MediaProjection lifecycle,
exported-component behaviour, notification content — were reviewed by reading the code and the
manifest, and are listed as manual checks in `docs/TESTING.md`.

## Receiving arbitrary files

Generic file transfer widens what a peer can send from "an image or a PDF" to "any bytes with any
name and any declared type". The peer is authenticated, not trusted: pairing proves which phone is
talking, never that the software on it is behaving.

### Malicious filenames

A received name is untrusted input that ends up labelling a file and, if mishandled, choosing
where it is written. `FilenameSanitizer` reduces a name to its last path segment before anything
else looks at it, so `../../etc/passwd` becomes `passwd`; strips control characters; replaces
filesystem-hostile characters; defuses Windows device names, which matter because a received name
may be shared onward to a desktop; and preserves spaces and Unicode, because mangling those is a
bug rather than a defence.

The name is never used to build a path in any case. A promoted file is named from its transfer
UUID, and the sanitised name is only a label. `ReceivedFilenameTest` asserts that no sanitised
name can contain a separator, across a list of hostile inputs.

Truncation is by **bytes** and walks whole code points. A 120-character CJK name is 360 bytes and
would overflow a 255-byte field; a byte-wise cut would split a surrogate pair and produce a string
that cannot be re-encoded.

### Oversized input and storage exhaustion

Every limit above is checked before allocation. A file over the limit is refused at the offer, so
nothing is opened. Overflow beyond the declared size is refused mid-stream rather than written.
Free space plus headroom is validated before acceptance. Batch totals bound what a single accept
can cost, and the pending-bytes and pending-files caps bound what repeated accepted batches can
cost — without those, each batch could be individually legal while together filling the partition.

### MIME spoofing

Content sniffing is deliberately **not** applied to generic files, and this is a considered
trade rather than an omission. Sniffing protects `IMAGE` and `PDF` because the app is about to
point a decoder at those bytes. Nothing decodes a generic file, so sniffing could not prevent
anything, while it would reject most ordinary files.

Spoofing is instead handled where it matters: at open time. The app never installs or executes
anything. Opening always goes through an Android chooser with the user selecting the handler, and
`FileTransferPolicy.requiresOpenWarning` checks **both** the declared MIME and the file extension
before that, because either can be wrong alone — a provider may report `application/octet-stream`
for an APK, and a sender may declare `text/plain` for something named `payload.apk`.

### Untrusted receiving applications

Open and Share hand the file to another app through a `FileProvider` content URI with a temporary
read grant, never a `file://` URI. The provider is `exported="false"` with
`grantUriPermissions="true"`, so it is unreachable except through a URI this app explicitly grants.
Its `file_provider_paths.xml` is scoped to the two cache subdirectories the transfer code writes,
not the whole cache and not external storage: a provider rooted at `.` would turn any traversal
that got past the sanitiser into a readable URI for arbitrary app-private data.

### Partial-file cleanup

Bytes land in app-private cache under a temporary name and are promoted only after every chunk
arrived in order, the byte count matched, the SHA-256 matched, and the stream flushed and closed.
A digest mismatch, cancellation, truncation or disconnect deletes the partial;
`GenericFileReceiveTest` asserts nothing is promoted and no partial survives on each of those
paths. Unclaimed files expire after 24 hours.

### No new permissions

Selection is through the Storage Access Framework. No storage permission is requested, and
`MANAGE_EXTERNAL_STORAGE` is neither declared nor needed.