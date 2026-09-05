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

## Mirroring reliability (Phase 2)

Diagnosed from code, fixed, and covered by tests. **Not hardware-verified: no device was attached
during this work.**

| Defect | State |
| --- | --- |
| **A. Control starvation** — one 8-slot queue shared by video, heartbeat and all control; heartbeat closed the session on a full queue | **Fixed.** Split into CONTROL/BULK/MEDIA with a strict-priority writer. Covered by `OutboundPriorityTest` and two `LoopbackSessionTest` cases over a real encrypted session. |
| **B. Capture surviving session termination** | **Not done.** `MirrorController` still captures `engine.activeSession.value` once at start. |
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
