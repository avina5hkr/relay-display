# Implementation status

Legend: **Complete** — built and covered by an automated test or verified by build output.
**Partial** — built, but a stated part is missing or unverified. **Deferred** — deliberately not
built. **Blocked** — cannot be verified in this environment.

Last updated after the Milestone 8 build.

## Milestones

| # | Milestone | Status | Evidence |
| --- | --- | --- | --- |
| 0 | Baseline and scaffolding | Complete | Baseline built green before any change; version catalog, package skeleton and four docs in place |
| 1 | Persistent role and mode | Complete | `SettingsRepositoryTest` (13); launch gate prevents chooser flash |
| 2 | Runtime state and permissions | Complete | `PermissionPolicyTest` (12), `ConnectionStateMachineTest` (22); manifest declares every permission and both FGS types |
| 3 | Discovery, secure pairing, trust | Complete | `HandshakeTest` (24), `PairingUriTest` (22), `LoopbackSessionTest` (14); NSD advertise/browse with lifecycle guards; manual entry fallback |
| 4 | Protocol and basic content | Complete | `FrameCodecTest`, `MessageCodecTest`, `TlvTest`, `SecureChannelTest`; text/QR/link plus blank, waiting, fit, brightness, immersive, rotation |
| 5 | Files and share integration | Complete | `TransferReceiverTest` (+ sniffer and cache, 32 tests); SAF pickers, ACTION_SEND text target, progress/cancel/cleanup |
| 6 | Availability and reconnection | Complete | `ReconnectBackoff` ladder tested; `connectedDevice` FGS with Disconnect/Pause; `NetworkMonitor` callbacks; no polling anywhere |
| 7 | Screen mirroring | **Partial** | Encoder, decoder, profile negotiation, degradation ladder, consent flow and both services are implemented and compile; **never executed on hardware** |
| 8 | Hardening and release readiness | **Partial** | Lint 0 errors, 313 tests green, R8 release build succeeds (19 MB → 2.5 MB); instrumentation and device matrix not run |

## Requirements

### Product

| Requirement | Status | Note |
| --- | --- | --- |
| One APK, persistent role | Complete | |
| Role chooser on first launch only | Complete | Launch gate holds the frame until DataStore reports |
| Confirmed role change in Settings | Complete | Dialog; stops runtime state before persisting |
| Operating mode ON_DEMAND / ALWAYS_READY / PAUSED | Complete | Survives process death; Paused is enforced in the state machine itself |
| Local-only, no cloud | Complete | No network dependency beyond the two phones |
| Fast path, mDNS, QR, manual entry | Complete | Fast path is subnet-gated so it fails fast |
| One-tap reconnect | Complete | |
| Honest connection state | Complete | 11 states; Connected requires an authenticated session |
| Bounded backoff with jitter | Complete | 1/2/4/8/15/30 s ±20 % |
| Heartbeats | Complete | 15 s / 45 s, tested with an injected clock |
| Idempotent commands with ids | Complete | `claimCommand` + ACK |

### Content

| Requirement | Status | Note |
| --- | --- | --- |
| Text and QR relay | Complete | Payload sent, not a screenshot; drawn as exact rectangles |
| Oversized-QR warning | Complete | Warns above 600 bytes, refuses above 1 200 |
| Links | Complete | https/http only; never auto-opened |
| Images | Complete | Bounds-first decode, EXIF orientation, fit/fill |
| PDF | Complete | One page at a time, page commands, password/corrupt handled |
| Share target | Complete | `text/plain`; display role offers a role change instead of switching |

### Platform

| Requirement | Status | Note |
| --- | --- | --- |
| Permissions requested at point of need | Complete | `PermissionGate` renders nothing where the permission does not exist |
| `ACCESS_LOCAL_NETWORK` on API 37+ only | Complete | Tested at five API levels |
| `connectedDevice` FGS + permission | Complete | Type applied from API 29, permission declared |
| `mediaProjection` FGS | Complete | Separate service; FGS starts before `getMediaProjection` |
| No boot receiver | Complete | None declared |
| SAF for user content | Complete | `OpenDocument`; no broad storage permission |
| API-37 calls behind guards | Complete | Lint clean; inline `SDK_INT` checks at call sites |

### Reliability and performance

| Requirement | Status | Note |
| --- | --- | --- |
| Survive rotation and process recreation | Complete | No state in the Activity; nav stack is saveable |
| No duplicate listeners across background/foreground | Complete | Single-writer coordinator; NSD generation counters |
| One authoritative session | Complete | A second connection is refused |
| Timeouts everywhere | Complete | Connect, handshake, resolve, transfer, SAS |
| Deterministic cleanup | Complete | Every resource closed in the `finally` that opened it |
| No broad `Throwable` catches | Complete | Every catch names a specific exception with a comment |
| Bounded caches and buffers | Complete | Cache by bytes and entries; queues bounded; streaming only |
| StrictMode in debug | Complete | |

## Not done, and why

| Item | Status | Reason |
| --- | --- | --- |
| Instrumentation tests executed | **Blocked** | The only attached device has a secure pattern lock; `ActivityScenario` cannot reach RESUMED behind it, and no emulator system image is installed. Tests are written and compile. |
| Two-device acceptance matrix | **Blocked** | Only one phone was attached. `docs/TESTING.md` has the full 17-row checklist. |
| Mirroring verified on hardware | **Blocked** | Same reason. The code paths compile and the profile negotiation is unit-testable, but no frame has been encoded or decoded on a real device. |
| Power and thermal measurements | **Blocked** | Needs the device matrix. Commands are in `docs/TESTING.md`. |
| Release signing config | **Deferred** | Release builds are unsigned; both phones install the debug APK. A `signingConfig` is needed before distributing. |
| Wi-Fi Direct transport | **Deferred** | The spec says not to make it the initial transport. `RelayLink` / `RelayDialer` / `RelayListener` are the seam if hotspot testing shows a need. |
| USB transport | **Deferred** | Explicit future work in the spec. |
| BLE wake/discovery | **Deferred** | Explicit future work in the spec. |
| Room database | **Deferred** | One peer and no transfer history; a validated DataStore record is sufficient and the spec allows it. |
| Mirror rotation renegotiation | **Partial** | Rotation is carried in `MIRROR_CONFIG` and the encoder can be recreated, but the controller does not yet watch for orientation changes and restart the encoder. Rotating the controller mid-mirror will letterbox until the mirror is restarted. |
| Audio capture during mirroring | **Deferred** | The spec says not to. |

## Known limitations

1. **No true extended desktop.** The app relays purpose-built content and can mirror this phone's
   screen. It cannot make other apps lay themselves out across two phones; no unprivileged app can.
2. **Keystore is required.** If the Android Keystore cannot create an EC key, pairing is disabled
   rather than falling back to a software key. This is a deliberate security choice.
3. **Discovery may not work on every network.** Client isolation and multicast filtering are
   common. QR pairing and manual entry exist for exactly that case.
4. **`FLAG_SECURE` content mirrors as black.** Android's decision, surfaced in the UI.
5. **Rate limiting is per address**, so it is weaker against an attacker who can change source
   address on the same LAN.

## Presentation-state and role-switch round

| Item | Status | Evidence |
| --- | --- | --- |
| Display can close received content locally | Complete | `PresentationToolbar`, `ContentRouter.dismissLocally`; `PresentationControllerTest` |
| Close is idempotent | Complete | `closing an already closed presentation is a safe no-op` |
| Close works while disconnected | Complete | `a local close while disconnected still frees the screen` |
| Local close synchronises to the Controller | Complete | `PRESENTATION_STATE` on every change; `RemotePresentationTrackerTest` |
| Remote close synchronises to the Display | Complete | `PRESENTATION_DISMISS` + reply; `a remote dismissal closes and reports` |
| Copy received text | Complete | `rememberTextCopier`, generic clip label, nothing logged |
| Stale events cannot overwrite current state | Complete | session id + Lamport revision; 8 dedicated tests |
| Sending is distinguished from Displayed | Complete | `RemoteContentStatus`; `sending is not displaying` |
| Role switch serialized and ordered | Complete | `RoleSwitchCoordinator`; `RoleSwitchCoordinatorTest` (12) |
| Repeated role-change tap is safe | Complete | `Mutex.tryLock`; `a repeated tap while switching is dropped, not queued` |
| One consistent phrase for closing | Complete | `CLOSE_ON_DISPLAY` = "Close on display" |
| About & Legal accurate and redesigned | Complete | four tabs; notices generated from resolved POMs |
| Notices survive R8 | Complete | `assets/third_party_licenses.txt` present in the release APK; verified by unzipping the R8 output |
| Third-party notice completeness | **Not complete** | The generated asset carries POM licence *names and URLs*, not full licence texts or `NOTICE` files. Sufficient for source distribution; **not** sufficient for binary distribution under Apache-2.0 section 4. See README for the classpath audit (165 components, 5 declaring no licence). |
| Compose UI tests for Close/Copy | Complete | **10/10 pass on the S22** (Android 16) over Wi-Fi debugging |
| Full instrumentation suite | Complete | **23/23 pass on both** the S22 (Android 16) and the Lenovo (Android 7.0) |
| Back after a role change shows the new role | Complete | reported from device; `RelayNavigator.rebaseForRole`; `RoleNavigationTest` (9) + 2 on-device tests |
| Navigation NPE on Activity recreation | Complete | found by instrumentation; fixed in `Screen.fromRoute`; pinned by `ScreenRouteTest` |
| Mode/fit rows tappable across full width | Complete | found by instrumentation; `selectable` with `Role.RadioButton` |
| Two-device regression matrix | **Blocked** | needs both phones attached and unlocked at once |
| Mirroring verified on hardware | **Partial** | reported working by the user; not re-verified after these changes |

## Generic file transfer

**End to end, reachable from the app, and exercised on two phones — now on `file-v2`, which
replaced `file-v1` after a code review found the batch consent was not bound to the files
transferred.** Controller: Samsung SM-S908E (API 36). Display: Lenovo K33a42 (API 24). See
`docs/TESTING.md` for the exact suites, counts and hardware evidence, and for what is still not
tested. States below are what is true, not what is intended.

| Piece | State |
| --- | --- |
| `ContentKind.FILE` wire code 3 | **Complete** |
| `file-v2` capability, announced by the display | **Complete.** `file-v1` is deliberately not advertised: it was unsafe, not merely older. A v1-only peer gets a clear refusal. |
| Manifest binding: transfer id + digest per entry, batch id + index on every offer | **Complete**, 25 unit tests |
| Immutable accepted manifest with per-entry states | **Complete.** `AcceptedBatch`, pure Kotlin. Replaced a `batchAccepted` boolean and an `acceptedBatchId` that was never read. |
| No peer-supplied sender name in the consent dialog | **Complete.** Removed from the wire; the dialog uses the authenticated peer name. |
| `FILE_BATCH_CANCEL`, one terminal state per batch, idempotent cleanup | **Complete**, verified on hardware in three cancellation cases |
| Batch cleanup is scoped to the batch that owns it | **Complete.** Cancellation is asynchronous, so a cancelled batch's `finally` could fire after the next batch had started and delete its spool file; the next send then failed with `ENOENT`. Found on hardware, pinned by `BatchLifecycleTest`. |
| Consent prompt visible over a presentation or mirror | **Complete.** Hosted at the app root; it was inside the dashboard, which is not composed while the display shows content, so a batch offered during mirroring could not be accepted. |
| A failed file cannot leave the receiver permanently BUSY | **Complete.** The receiver marks the entry failed and settles the batch itself; the sender also reports an incomplete batch. Three `AcceptedBatchTest` cases. |
| Cancellation notifies the peer before cancelling the coroutine | **Complete**, and now unit-tested: `BatchLifecycleTest` drives two real routers over a loopback session and asserts the display receives `FILE_BATCH_CANCEL`. |
| Receiver-initiated end (reject or expiry) releases the sender | **Complete.** The controller had no `FileBatchCancel` handler, so it sat in its 120 s decision timeout and refused the next batch; found by `BatchLifecycleTest`. |
| Receiver-side expiry of an unanswered consent prompt | **Complete**, unit-tested with an injected expiry duration (no test waits out the real 150 s). Not exercised on hardware at the production duration. |
| Outbound spooling: each source read exactly once | **Complete for generic files**, 13 unit tests; spool cleanup verified on hardware. **The image/PDF presentation path still reads its source twice** (`ContentSource.prepare` then reopen) and was left unchanged. A one-shot or changing provider stream there fails the receiver's digest check, which is a visible failure rather than wrong content, because those transfers have no manifest to disagree with. Worth migrating when that path is next touched. |
| Save As off the main thread with observable state | **Complete**, 12 unit tests including a thread-identity assertion, 5 instrumentation tests, and a 19.9 MB hardware run with no ANR |
| Cache split into `received/`, `presentation/`, `incoming/`, `spool/` | **Complete**, verified on hardware |
| Legacy cache migration, idempotent, deletes nothing | **Complete**, 5 unit tests and one real installation migrated on the Lenovo |
| Consent dialog usable at 20 files | **Complete.** Bounded scrollable list with a window-relative height cap, and the summary and executable warning both kept outside the scroll region. Instrumentation-tested on both phones in portrait and landscape at font scales 0.85, 1.3 and 1.5. Display-size enlargement **not tested**. |
| Received-file actions as a 2x2 grid, >=48dp targets, TalkBack labels | **Complete**, instrumentation-tested on both phones |
| `FILE_BATCH_OFFER / ACCEPT / REJECT`, bounded nested manifest | **Complete**, 13 codec tests including truncated, over-long, trailing-byte and overflowing frames |
| "Peer too old" refusal for a v1-only or unversioned peer | **Complete.** `sendFileBatch` checks `peerCapabilities` and the Send screen says so before the user picks anything. Not exercised against a real old peer, because none exists — no version has been published. |
| Sizes measured before offering; no unknown-size sentinel on the wire | **Complete**, 12 tests. Replaces the old contradiction between the picker, this protocol and the receiver. |
| Empty files, kind-aware | **Complete.** Zero bytes is valid for `FILE`, still invalid for `IMAGE`/`PDF`. |
| Filename sanitising: byte **and** character limits, code-point safe, extension preserved | **Complete**, 22 tests including the ~180-character ASCII `.pdf` regression |
| Metadata and batch validation, overflow-safe totals | **Complete** |
| Multi-file batch state machine, wired to production | **Complete.** `ContentRouter.sendFileBatch` drives `FileBatchState`; the Send screen renders it. |
| Files action, SAF multi-select, review list with remove/send/cancel | **Complete.** Reachable from a **Files** tile on the controller dashboard as well as the Send screen; both drive one `SendViewModel` keyed "send", so a batch started from either shows in both. |
| Incoming-batch prompt: sender, count, names, sizes, Accept all / Reject | **Complete**, instrumentation-tested. Back and outside-tap both reject rather than leaving the sender waiting. |
| Per-file progress, "N of M", cancel, retry-from-start | **Complete.** Retry re-offers only retryable failures and never resends a verified file. |
| Received-files list with Open / Save as / Share / Delete | **Complete**, instrumentation-tested |
| `FileProvider`, narrowed to verified files only | **Complete.** `incoming/` removed: partials are unverified, so no URI is ever granted to one. The declared path had never matched the cache layout, so the first real use crashed; fixed and pinned by `FileProviderPathTest`. |
| Executable warning before opening; never auto-installs or executes | **Complete**, instrumentation-tested on both phones |
| Metadata sidecar so the list survives a restart | **Complete**, 17 cache tests |
| Retention limits actually enforced, and able to hold one legal batch | **Complete.** `ContentCache` takes its budgets from `FileTransferPolicy`; expiry is swept at startup. |
| Streaming, digest, ordered chunks, atomic promote | **Complete.** Reuses `TransferReceiver`, extended and tested for `FILE`. |
| File bodies on `TrafficClass.BULK`; control stays `CONTROL` | **Complete.** `TransferComplete` had to move to `BULK` too: on `CONTROL` it overtook its own final chunks and failed large transfers. `TransferCancel` stays on `CONTROL` deliberately. |
| **Auto-accept setting** | **Deferred, deliberately.** See below. |
| **Foreground-service integration, notification progress** | **Deferred.** A batch sent with the app in the background is bounded by the existing session lifetime; there is no notification showing batch progress. |
| **Resume after process death** | **Not implemented.** The protocol has no resume, partials are deleted rather than continued, and nothing claims otherwise. |
| Instrumentation tests | **48 of 48 passing on both phones** (Lenovo API 24 and S22 API 36), including 13 file-transfer UI tests and 4 FileProvider path tests. |
| Two-device transfer matrix | **Run.** 21 cases plus the full interruption matrix (network drop, both force-stops, five cancel/resend cycles, transfer during mirroring) pass, including a 0-byte file, a 180-character filename, a 19.9 MB APK, restart persistence, reconnect without re-pairing, and the executable warning. Share, mid-flight cancel, retry-after-failure, concurrent mirroring and the batch limits at their boundaries are **not** exercised. |

### Auto-accept: deferred on purpose

There is no setting to accept incoming files without being asked, and the confirmation cannot be
turned off. This is a deliberate omission rather than missing work: the prompt is the only thing
standing between a paired peer and arbitrary bytes on this phone's storage, and an auto-accept
switch is exactly the setting a user enables once for convenience and then forgets. If it is added
later it needs its own design — at minimum a per-peer scope, a size ceiling and an expiry — and
none of that is built. Until then, every batch is confirmed by a person.

### Mirroring interaction

**Routing implemented; concurrent behaviour not tested on hardware.** File bodies travel as
`TransferChunk`, which `trafficClass()` maps to `TrafficClass.BULK` — backpressured and ranked
below `CONTROL`, so heartbeats cannot be starved by a transfer the way they were by video. Batch
negotiation, accept, reject, cancel and acknowledgement all stay on `CONTROL`, and no new socket
or plaintext path was introduced. What has **not** been done is running a file batch and a live
mirror at the same time on the two phones and measuring what happens to either.

## Mirroring reliability (Phase 2)

Diagnosed from code, fixed, and covered by tests. **Not hardware-verified: no device was attached
during this work.**

| Defect | State |
| --- | --- |
| **A. Control starvation** — one 8-slot queue shared by video, heartbeat and all control; heartbeat closed the session on a full queue | **Fixed.** Split into CONTROL/BULK/MEDIA with a strict-priority writer. Covered by `OutboundPriorityTest` and two `LoopbackSessionTest` cases over a real encrypted session. **A real latent defect, but hardware testing showed it is _not_ the cause of the reported one-minute failure** — see `docs/TESTING.md`. |
| **B. Capture surviving session termination** | **Fixed and hardware-verified.** Capture is now bound to the exact `RelaySession` that started it and stops the moment that session ends or is replaced. Measured on device: killing the Display process stopped capture in **1 s** (previously still running after 4 minutes), with `dumpsys media_projection` reporting `null`, the foreground service gone and the indicator cleared. A reconnect deliberately does **not** rebind capture — resuming silently would stream the user's screen to a session they never consented to. |
| **C. Reliable mirror negotiation** | **Partly.** MirrorStart/Config/Stop are now CONTROL class, so they are no longer dropped under video pressure. There is still no mirrorId/epoch, no ACK and no MirrorReady handshake. |
| **D. Decoder failures not reported to the Controller** | **Not done.** |
| **E. Surface lifecycle modelling** | **Not done.** |
| **F. Oversized encoded frames (192 KiB cap)** | **Not done.** A keyframe over the cap still throws `PAYLOAD_TOO_LARGE` and closes the session. |
| **G. Inbound `DROP_OLDEST` shared by control and media** | **Not done.** `RelayEngine._messages` is still one `MutableSharedFlow(extraBufferCapacity = 32, DROP_OLDEST)`. |
| **H. Mirror health protocol** | **Not done.** |
| **I. MediaProjection service review** | **Not done.** |
| **J. Codec robustness review** | **Not done.** |

## First two-device run

Everything below was found by running the two phones against each other for the first time, not by
reading code. Each is fixed and, except where noted, re-verified on the hardware that exposed it.

| Found | Fix | Re-verified |
| --- | --- | --- |
| "Displays on this network" could never populate — discovery ran only inside a connection attempt | one mDNS browse refcounted between its two consumers (`BrowseLease`) | yes: the list fills, and pairing through it works |
| A Display went on naming a peer that had hung up, indefinitely | `ConnectionEvent.PeerDisconnected` dispatched by the accept loop | yes: flips to "Waiting" in ~5 s |
| A role change away from Display hung for 4 s and the engine never restarted | close the bound listener from `stop()`, not from a coroutine that cannot resume | yes: `begin` and `complete` in the same second |
| Closing the mirror on the Display left the Controller capturing, indicator lit | any non-`MIRRORING` phase report stops the local capture | **no** — see `docs/TESTING.md` |

## UI round

The app worked and looked like a settings page. This round gave it a shape without adding a
feature.

| Change | Why |
| --- | --- |
| Settings moved from a full-width button at the bottom of a scroll to a gear in the top bar's **end** slot | the start slot belongs to a navigation icon; one corner, one meaning, on every screen |
| `ConnectionHero` replaces a flat status card | the connection state is the first question a dashboard has to answer, and colour survives a cracked panel better than a line of text |
| Six `ActionTile`s replace a column of identical full-width buttons | scannable by shape; six fit where two used to |
| Tiles now do what they say | QR/Text/Link open the composer **on that kind**; Image/PDF go straight to the system picker; Share screen goes straight to the consent dialog. Before, all six opened the same screen |
| The redundant "Open send screen" button is gone | seven controls led to one destination |
| The send screen leads with the kind you asked for, other two one tap away | changing your mind should not cost a trip back to the dashboard, and the draft is shared so nothing typed is lost |
| Pairing screen: detail bar, hero, outlined section cards, whole-row device targets | it was the last screen still on the old header-and-bottom-button pattern |
| The six-digit code card is tonally distinct and uses the largest type in the app | it is the only security decision the user is ever asked to make |
| System Back on the pairing screen now cancels the attempt | the Cancel button tore the attempt down and the Back gesture quietly left it running |
| Plain cards are outlined `surface`; only `ConnectionHero` is filled | a filled status card among four other filled cards has no weight |

### Deliberately not changed

| Item | Reason |
| --- | --- |
| Reconnect attempt counter resetting across engine restarts (RC-5) | Cosmetic status-text issue; recorded in `diagnostics/FINDINGS.md`. Fixing it means moving `ReconnectBackoff` ownership out of the run loop, which touches the reconnect path this round did not otherwise disturb. |
| Listener port churn on reconnect (RC-6) | Costs one backoff step during recovery. Same reasoning: it is a change to the reconnect path, and the task scoped this round to state consistency. |
| Application licence | `GPL-3.0-or-later`. The unmodified GPL-3.0 text is in `LICENSE`; the About screen carries the notice GPLv3 section 5(d) requires, with a link to the source repository. |
