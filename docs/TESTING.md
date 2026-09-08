# Testing RelayDisplay

## Commands

```bash
# JDK 25 is required (matches gradle/gradle-daemon-jvm.properties). Android Studio's bundled JBR
# is one, so this works without installing anything extra:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew testDebugUnitTest      # JVM unit + integration tests
./gradlew lintDebug              # must report 0 errors
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # R8-minified release APK
./gradlew connectedDebugAndroidTest   # instrumentation; needs an unlocked device
```

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` is the full gate.

## Automated coverage

All unit and integration tests run on the JVM with no device. That is a deliberate design
constraint: the protocol codec, the crypto, the state machine, the transfer logic, the QR encoder
and the validators were all written to have no Android dependency, so they are testable in
milliseconds rather than minutes.

| Suite | Tests | What it covers |
| --- | --- | --- |
| `TlvTest` | 14 | ordering, duplicate tags, truncation, `0xFFFFFFFF` lengths, strict UTF-8, field caps |
| `FrameCodecTest` | 15 | golden header bytes, bad magic, version, flags, reserved byte, negative/oversized lengths, one-byte-at-a-time reads, several frames per buffer, mid-frame EOF |
| `MessageCodecTest` | 18 | round-trip for every message type, golden encodings, determinism, unknown types, oversized text/chunks, missing and wrong-width fields |
| `HkdfTest` | 9 | RFC 5869 appendix A vectors 1–3, constant-time compare |
| `SecureChannelTest` | 14 | seal/open, replay, reorder, renumber, tag tamper, cross-direction, plaintext-after-handshake, oversize, key wiping |
| `HandshakeTest` | 24 | key agreement, SAS derivation, pinning, QR token success/failure/replay, forged and reflected signatures, same-role refusal, malformed keys, MITM |
| `PairingUriTest` | 22 | round trip, wrong scheme/version, expiry, TTL cap, weak tokens, bad fingerprints, hostname refusal, repeated parameters, unsafe ids, no secrets in `toString` |
| `ConnectionStateMachineTest` | 26 | the happy path, pause from every state, paused ignores everything, hard reset, drops with/without intent and auto-reconnect, unrecoverable failures, retry scheduling, stale events, **a peer that hangs up while this device keeps listening** |
| `BrowseLeaseTest` | 11 | one browse shared by two consumers: start once, survive until the last leaves, extra releases cannot go negative, reset, concurrent retain/release |
| `ListenerShutdownTest` | 3 | closing a listener unblocks a thread parked in `accept()` — the property `stop()` relies on |
| `SessionCoordinatorTest` | 9 | single-writer state, intent tracking, Pause blocking every later request, Always Ready, hard reset, no reconnect after a user disconnect |
| `ReconnectBackoffTest` | 7 | the ladder, saturation, reset, jitter bounds, no busy loop, two devices desynchronising |
| `PermissionPolicyTest` | 12 | API 23/33/34/36/37 decisions, LAN permission only on 37+, notification denial degrades, never asks for location or storage |
| `SettingsRepositoryTest` | 13 | defaults, round trips, corrupt enum fallbacks, role change semantics, clamping, persistence across repositories |
| `QrEncoderTest` | 12 | quiet zone, finder pattern, size growth, byte-not-character limits, determinism, dense warning |
| `UrlValidationTest` + `FilenameSanitizerTest` | 20 | scheme allow/deny, malformed URLs, userinfo stripping, path traversal, reserved names, control characters |
| `TransferReceiverTest` + `MimeSnifferTest` + `ContentCacheTest` | 32 | verified transfers, size/space/type refusals, out-of-order and oversized chunks, truncation, digest mismatch, content/type mismatch, cleanup, cache eviction |
| `PresentationControllerTest` | 16 | local close, idempotent close, close while disconnected, A-then-B ordering, stale session and stale revision rejection, Lamport clock monotonicity, copyable-content rules |
| `RemotePresentationTrackerTest` | 12 | Sending is not Displayed, only a matching report reaches Displayed, dismissal reflected, duplicate and older reports dropped, foreign session ignored, reconciliation trusts the Display |
| `RoleSwitchCoordinatorTest` | 12 | persistence last, teardown order, repeated tap dropped, Switching then Completed, screen cleared, mode reset, On Demand starts nothing |
| `LoopbackSessionTest` | 14 | full handshake and relay over an in-process transport, plus fault injection |

**Total at that point: 331 tests, all passing.** (See the current total below.)

Three suites were added this round, each pinning a bug the two-device run found:
`BrowseLeaseTest` (11) for the shared mDNS browse refcount, `ListenerShutdownTest` (3) for the
contract `RelayEngine.stop()` depends on, and four more `ConnectionStateMachineTest` cases for
`PeerDisconnected`.

### Generic file transfer

| Suite | Tests | Covers |
| --- | ---: | --- |
| `FileTransferPolicyTest` | 20 | per-file and batch limits at and over the boundary, MIME fallback, over-long and control-character MIME, executable warning by MIME **and** extension including a mismatched declaration, case-insensitivity, image/PDF rules unchanged |
| `ReceivedFilenameTest` | 22 | traversal (relative, absolute, Windows, traversal-only), no separator survives any hostile input, control characters, hostile characters, Windows device names, spaces/CJK/Cyrillic/emoji preserved, both budgets applied together, the ~180-character ASCII `.pdf` regression, character cap with a generous byte cap and vice versa, over-long extension not reserved, truncation never splitting a code point |
| `FileBatchStateTest` | 20 | initial state, known vs indeterminate totals, progress across files, percentage clamping, empty file as 100%, settling only when every phase is terminal, verifying not terminal, cancel keeping completed files, idempotent cancel, rejection, retryable vs non-retryable failure, no retry before settling, disconnect marking interrupted, retry-from-start resetting only the retried file |
| `GenericFileReceiveTest` | 19 | end-to-end accept/verify/promote, any MIME, empty file, APK transferred but flagged, digest mismatch, out-of-order chunk, duplicate chunk index, wrong transfer id, truncated finish, overflow beyond declared size, size limit, negative size, chunk size beyond the frame limit, size changed between offer and start, cancellation cleanup, hostile filename, capability versioning, old-peer refusal |
| `FileBatchManifestTest` | 14 | manifest round-trip with order preserved, a full 20-file batch, multi-byte names, empty MIME, and the hostile cases: truncated mid-manifest, trailing bytes, empty batch, over the file limit, negative size, size over the per-file limit, two `Long.MAX_VALUE` sizes, an inflated name-length prefix, encoding stability |
| `ContentPreparationTest` | 12 | measured size and digest describing the same bytes, unknown declared size resolved, over- and under-reported sizes corrected, empty file, source read exactly once, limit enforced during the read rather than from the declaration, exactly-at-limit accepted, read failure reported, digest equality by content |
| `ContentCacheRetentionTest` | 18 | the retention invariant, budgets matching policy, a full batch surviving receipt, name/type persistence across instances, missing sidecar, sidecar whose size disagrees with the file, a sidecar attempting a path in the display name, sidecars excluded from budgets, oldest-first eviction taking sidecars, byte-budget eviction, expiry in and out of the window, partial sweeping, deletion, extension fallback |
| `IncomingBatchTest` | 13 | totals including all-empty and worst-legal-case (no overflow), executable detection by extension and by MIME including disguises, "apk" as a substring not tripping it, hostile name sanitised before it reaches a screen, batch refusals for count/bytes/empty/overflowing totals |

**Unit tests: 486 run, 0 failures** (up from 418).

Four of those were added after hardware testing, in `OutboundPriorityTest`: they pin the traffic
class of `TransferComplete`, `TransferCancel`, `TransferStart` and the three batch messages. See
"Two bugs the device found" below.

### Instrumentation tests, on hardware

`FileTransferUiTest`, 13 tests: the About screen rendering its mark; the incoming prompt naming
the sender, every file and every size; accept and reject each reporting exactly once; the
executable call-out; the received-files empty state; all four per-file actions present; delete
asking first and only reporting after confirmation; Keep cancelling it; an APK warning before
anything leaves the app; an ordinary file not warning; type and size per row; one row per file.

`FileProviderPathTest`, 4 tests: that a file the cache wrote can actually be turned into a
`content://` URI, that the URI reads back the right bytes, that a partial in `incoming/` cannot be
shared, and that the cache root is not exposed.

| Device | Full suite |
| --- | --- |
| Lenovo K33a42, Android 7.0 (API 24) | **OK (50 tests)** |
| Samsung SM-S908E, Android 16 (API 36) | **OK (50 tests)** |

Run with `adb shell am instrument -w -r com.avinash.relaydisplay.test/androidx.test.runner.AndroidJUnitRunner`
after installing both APKs.

**Do not trust `connectedDebugAndroidTest`'s summary on its own.** On this setup it printed
`BUILD SUCCESSFUL` while running **zero** tests and writing a report that says "0 tests, 0
failures" — a false pass, twice. The `am instrument` output above is what was actually believed.
It is also worth knowing that `connectedDebugAndroidTest` *uninstalls* the app first, which will
wipe a release-signed install and its data.

The About-screen test is the regression test for a real crash: `AboutScreen` rendered
`painterResource(R.mipmap.ic_launcher)`, which on API 26+ resolves to
`mipmap-anydpi-v26/ic_launcher.xml` — an `<adaptive-icon>`, which `painterResource` cannot inflate.
Opening About therefore threw on every modern phone. No unit test could see it, because the failure
is in resource inflation on a device.

### Two-device file transfer, on hardware

Controller: Samsung SM-S908E (API 36). Display: Lenovo K33a42 (API 24). Both on Wi-Fi, paired.

| Case | Result |
| --- | --- |
| **Files** tile on the dashboard, disabled while disconnected | Pass |
| Files tile opens the picker; review list appears on the dashboard in place | Pass |
| Sending from the dashboard: 2 files, "2 of 2 sent", each row "sent" | Pass |
| Files action visible and enabled when connected on the Send screen | Pass |
| SAF multi-select, 4 files chosen, `*/*` | Pass |
| Review list: name, MIME, size per file; total "4 file(s), 487 B" | Pass |
| Remove one file: count and total recalculated to "3 file(s)" | Pass |
| Display prompt: sender name, count, total, every name and size | Pass |
| Accept all, then 3 files transferred and verified | Pass, ~450 ms |
| **A 0-byte file transfers and lands as 0 bytes** | Pass |
| **A 180-character ASCII `.pdf` arrives as 120 characters, still `.pdf`** | Pass |
| Files named from transfer UUID on disk, never from the peer's name | Pass |
| No partial left in `incoming/` after success | Pass |
| Received list: newest first, name/MIME/size, four actions each | Pass |
| List survives force-stop and relaunch (metadata sidecar) | Pass |
| Status honestly reads "Not connected" after the process restart | Pass |
| Reconnect after reinstall without re-pairing | Pass |
| Open a PDF: opens in an external viewer through a `content://` URI | Pass |
| An APK batch: prompt shows the executable call-out | Pass |
| A 19.9 MB APK transfers and verifies | Pass, ~8 s (**after a fix; see below**) |
| Open an APK: warns first, app stays foreground, nothing installed | Pass |
| Cancel on that warning: file untouched, nothing installed | Pass |
| Delete: asks first, then removes payload **and** sidecar, no orphan | Pass |
| Save as: `CreateDocument`, name pre-filled from the sidecar, file written | Pass |

Not exercised on hardware: Share (the chooser path is the same code as Open, which was), cancelling
a transfer mid-flight, retry after a real failure, a batch running concurrently with an active
mirror, and the 20-file/200 MiB limits at their boundaries.

### file-v2 review fixes: tests and evidence

Run: `./gradlew --no-daemon validateVersion verifyReleaseGuards testDebugUnitTest lintDebug
assembleDebug assembleDebugAndroidTest`, plus `compileReleaseKotlin lintRelease minifyReleaseWithR8`
for the release path (no signing secrets involved). **Unit tests: 568 run, 0 failed.** Lint: 24
findings, all pre-existing (version-upgrade suggestions, unused strings, four
`AutoboxingStateCreation` in `PresentationScreen.kt`), none in the files this work touched.

| Suite | Tests | Covers |
| --- | ---: | --- |
| `AcceptedBatchTest` | 25 | exact match; wrong batch id; no batch at all; unknown transfer id; wrong manifest index; wrong name, MIME, size and digest (including a one-byte digest change); name and MIME normalisation applied to both sides; a traversal dressed as the approved name; extra file beyond the accepted count; replay of a completed file; a second offer of one in flight; re-offer after failure; entry state transitions and illegal ones; settling; idempotent cancellation keeping verified files; nothing offerable after cancellation; refusal reasons containing no peer content |
| `SourceSpoolTest` | 13 | one-pass copy/measure/digest; zero-byte source; monotonic progress; a source that can only be opened once; a source that returns different bytes on its second open; exceeding the limit mid-read; exactly at the limit; throwing part way through; zero-byte reads not mistaken for EOF; a security failure on open; cancellation deleting the partial spool; the spool directory sweeping only its own files |
| `FileExporterTest` | 12 | byte-for-byte copy; empty file; **the copy running off the calling thread**; never reading the file whole; an unopenable destination; a write failure mid-copy requesting cleanup; a short copy never reported as success; a security failure; unknown expected size; both streams closed on failure; zero-byte reads; cancellation requesting cleanup |
| `FileCapabilityTest` | 7 | two updated peers negotiate; a v1-only peer is refused; no file capability; a peer advertising both is spoken to as v2; an unknown future version alone is not enough; image and PDF independent of the file version |
| `ContentCacheRetentionTest` | 25 | the retention invariant and budgets; a full batch surviving receipt; metadata persistence, missing sidecar, size disagreement, a sidecar attempting a path; **a payload promoted without metadata is a presentation file, not a received file**; presentation and received not colliding; legacy migration of a generic file, of an unlabelled payload, idempotence, no legacy directory, an orphan sidecar; spool files swept and never listed; eviction and expiry |
| `FileBatchManifestTest` | 17 | round trip with order preserved; a full 20-file batch; multi-byte names; empty MIME; transfer id and digest surviving byte-for-byte; a manifest naming the same transfer twice; truncated, trailing-byte, empty, over-limit, negative-size, oversize and overflowing frames; an inflated name-length prefix; encoding stability |
| `MessageCodecTest` | 23 | a golden round-trip sample for **every** message type, now including `FILE_BATCH_CANCEL`, a bound generic `CONTENT_OFFER`, and an unbound image offer; determinism |
| `OutboundPriorityTest` | 10 | `TransferComplete` sharing the chunk queue (the ordering race must not return); `TransferCancel` and `FileBatchCancel` overtaking the backlog; `TransferStart` on control; batch negotiation on control |

Also still passing unchanged: `FileBatchStateTest` (20), `IncomingBatchTest` (13),
`ReceivedFilenameTest` (22), `FileTransferPolicyTest` (20), `GenericFileReceiveTest` (19),
`ContentPreparationTest` (12).

### Batch lifecycle, over a real encrypted session

`BatchLifecycleTest`, 12 tests. Two real `ContentRouter`s over the loopback transport, so the
handshake, record encryption, the priority writer, both routers and both on-disk caches are all
genuine and only the socket is replaced. That matters because the bug being pinned is an *ordering*
bug, and ordering only exists on a real wire.

| Case | What it proves |
| --- | --- |
| cancel while the consent prompt is open | `FILE_BATCH_CANCEL` actually reaches the display. Before the fix nothing was transmitted at all, because `streamOneFile` rethrows `CancellationException` before it can send. |
| cancel during an active transfer | Same, with 40 MiB in flight. Deterministic: it polls until bytes are demonstrably moving, then cancels, rather than sleeping a guessed interval. |
| cancel three times in a row | Idempotent; one clean terminal state. |
| a cancel naming an unknown batch | Ignored, and the live batch is untouched. |
| an accept arriving after the controller gave up | Ignored; the batch stays terminal and nothing restarts. |
| an unanswered prompt expires locally | Prompt cleared, no partial, and the controller is told. Uses an injected expiry, so no test waits out the real 150 s. |
| a new batch immediately after an expiry | Accepted, prompts again. |
| a new batch immediately after a cancellation | Accepted and carried to completion. **This is the regression**: the display used to hold consent and refuse with `BUSY`. |
| three cancel/resend cycles | No prompt, partial or spool file left after any round. |
| an accepted batch delivers the file | Both sides terminal, consent spent, spool released. |
| a generic offer with no accepted batch | Refused over the real wire; nothing written, no partial created. |
| cancel, then immediately resend and accept | The second batch's spool file survives the first batch's late cleanup and the file actually arrives. Pins defect 3 below. |

Every cancellation case asserts the same four things: no partial in `incoming/`, no spool file, no
lingering consent, and a subsequent batch works.

**Two defects these tests found, neither reachable by inspection:**

1. **The controller had no handler for `FileBatchCancel`.** A receiver-initiated end -- the user
   rejecting, or the consent prompt expiring -- left the sender sitting in its 120 s decision
   timeout with `batchJob` still active, so the next batch was refused with "a batch is already
   being sent". The same class of stuck state as the original bug, in the opposite direction.
   Fixed by handling it on the controller side and running the same terminal cleanup.
2. **The executable warning could be pushed out of the consent dialog.** With a 20-file manifest
   in landscape at `font_scale` 1.3 on the Lenovo, the warning sat below the bounded file list and
   fell outside the dialog. Found by running the instrumentation suite rotated and at a large font
   scale. Fixed by moving the warning above the list -- both it and the summary are what the
   decision is made on, so neither may be displaceable -- and by making the list's height cap a
   fraction of the window rather than a fixed 200dp.

### Interruption matrix, on the two phones

Controller: SM-S908E (API 36, `R5CT238SFMB`). Display: Lenovo K33a42 (API 24, `252fce95`). Same
debug APK on both, paired over the S22's hotspot by comparing the six-digit code (`220 940`,
verified identical on both screens before confirming).

| Case | Result | Evidence |
| --- | --- | --- |
| Network drop mid-transfer, reconnect, send again | **Pass** | Wi-Fi disabled on the companion during a 19.9 MB send; both sides ended cleanly; after re-enabling, both reconnected **without re-pairing** and a 38 MB two-file batch completed (`2/2 received`, `batch ended: completed`) |
| Force-stop the controller mid-transfer, reopen, reconnect, send | **Pass** | display logged `batch ended: the connection ended`, partials 0; the spool file the killed process left was **swept at startup** (`swept 1 spooled file(s)`), 1 to 0; reconnected and sent again |
| Force-stop the companion mid-transfer, reopen, reconnect, send | **Pass** | controller logged `session ended` and released its spool; the partial the killed companion left was swept at startup, 1 to 0; reconnected and a fresh send completed |
| Five cancel/resend cycles, no reconnect | **Pass** | each round offered and cancelled; `partials=0 spool=0` after every round; no `BUSY` |
| Cancel then immediately resend and accept | **Pass, after a fix** | see defect 3 below |
| 19.9 MB transfer while mirroring is active | **Pass, after two fixes** | mirror `started 596x1280@24`; prompt shown **over** the mirror; `received FILE (19910151B)`, `batch ended: completed` in about 6 s; mirror service still running and still `showing MIRRORING` afterwards |

After every interruption: no permanent `BUSY`, no stuck dialog, no partial in `incoming/`, no spool
file, reconnection worked, another file sent successfully, and mirroring stayed active.

### Three defects the interruption matrix found

None was reachable by inspection or by any unit test that existed at the time.

1. **A single failed file left the receiver permanently `BUSY`.** When one transfer failed
   mid-flight, the receiver left its manifest entry `PENDING`, so the batch never settled, consent
   never expired, and every later batch was refused as `BUSY` for the life of the session. The
   sender did not report it either -- it ended its own batch without telling the receiver. Fixed on
   both sides: the receiver marks the entry failed when a transfer aborts (self-healing, so it
   recovers even if the notification is lost), and the sender sends `FILE_BATCH_CANCEL` whenever a
   batch ends without delivering everything. The receiver's half is pinned by three
   `AcceptedBatchTest` cases.
2. **The consent prompt could not be seen while the companion was mirroring.** It was hosted inside
   `DisplayHomeScreen`, but when the display is showing content or a mirror `RelayAppRoot` returns
   early into `PresentationSurface`, so the prompt was never composed. A batch offered during
   mirroring simply could not be accepted -- nothing was left stuck, because the sender's timeout
   and the receiver's local expiry both fire, but the feature was unusable. Fixed by hosting the
   dialog at the app root, above that early return; an `AlertDialog` gets its own window so it
   draws over the presentation surface.
3. **A cancelled batch's cleanup deleted the next batch's spool file.** `batchJob.cancel()` flips
   `isActive` false immediately but the coroutine's `finally` runs later, so a cancelled batch's
   cleanup could fire after the user had started the next one -- and it deleted whatever `outbound`
   pointed at, which by then was the new batch. The next transfer then failed at stream time with
   `FileNotFoundException ... ENOENT`, intermittently, depending on how long the user took to
   accept. This is what the two "transient" failures reported earlier actually were. Fixed by
   scoping cleanup to the batch that owns it (`releaseOutbound(batch)`), pinned by
   `BatchLifecycleTest`.

Two supporting observability gaps were closed on the way, because both defects were hidden behind
messages that said nothing: the receiver's `BUSY` refusal was silent, and the sender reported only
"could not read the file" without the exception. Defect 3 was found by adding that exception to the
log.

### Instrumentation, on both phones

```
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r \
  com.avinash.relaydisplay.test/androidx.test.runner.AndroidJUnitRunner
```

| Device | Serial | Result |
| --- | --- | --- |
| Lenovo K33a42, Android 7.0 (API 24) | `252fce95` (USB) | **OK (65 tests)** |
| Samsung SM-S908E, Android 16 (API 36) | `R5CT238SFMB` (USB) | **OK (65 tests)** |

Same debug APK on both, sha256 `6d30a340...`. New this round: a 20-file consent dialog keeping
Accept and Reject reachable, reporting its total and sender, scrolling to its last file, and still
showing the executable warning; per-row accessibility descriptions; all four received-file actions
displayed with a >=48dp touch target and TalkBack labels naming the file; save-in-progress
disabling a second save; save failure with dismissal; save completion; a save on one row not
marking another; the list unaffected when idle.

**Font scale and orientation, on the Lenovo.** The 26 `FileTransferUiTest` cases pass in every
combination tried: portrait and landscape, at `font_scale` 0.85 (the phone's own setting), 1.3 and
1.5. The **full 65-test suite now passes in every combination**: portrait and landscape at 0.85, 1.3 and
1.5. Three tests previously failed at 1.3 and above --
`RoleSelectionTest.choosingControllerOpensTheControllerDashboard`,
`RoleSelectionTest.choosingDisplayOpensTheDisplayDashboard` and, in landscape,
`DashboardChromeTest.tappingTheIconOpensSettings`. All three were **test defects, not UI defects**:
the role chooser and the settings screen are both already scrolling columns, so the controls are
present and reachable, but the tests clicked and asserted visibility without scrolling. At a large
font scale the confirm button sits below the fold, `performClick` taps a point outside the viewport
and the tap does not land, and `assertIsDisplayed` asks about actual visibility. Fixed by adding
`performScrollTo()` before the interaction, which is what a user does. The device's font scale and rotation were restored to the user's own values afterwards
(`font_scale` 0.85, `user_rotation` 0).

**Read the instrumentation output, not the Gradle exit code.** `connectedDebugAndroidTest` on this
setup has twice printed `BUILD SUCCESSFUL` while running **zero** tests, and once reported success
after an install failure. Every count above comes from the `OK (N tests)` line of `am instrument`.

### Two-device hardware verification, file-v2

Controller: SM-S908E (API 36). Display: Lenovo K33a42 (API 24). Same debug APK on both, paired over
Wi-Fi by comparing the six-digit code (`571 970`, verified identical on both screens before
confirming).

| Case | Result | Evidence |
| --- | --- | --- |
| Two-file batch offered with the bound manifest | **Pass** | prompt lists both names and sizes, sender shown as the authenticated peer name |
| Per-file offer matched against the accepted manifest | **Pass** | display log `file 1 of 1 matched the accepted manifest` |
| Batch reaches a terminal state on both sides | **Pass** | display `batch finished: 1/1 received` then `batch ended: completed`; controller `batch done: 1/1 sent` |
| **Controller cancels before the companion answers** | **Pass** | display log `batch cancelled by the sender (CANCELLED)` then `batch ended: cancelled by the sender`; dialog dismissed; no files written |
| **A new batch immediately after cancelling, no reconnect** | **Pass** | second offer accepted normally; no `BUSY` |
| **Controller cancels 2.4 s into a 19.9 MB transfer** | **Pass** | display terminal as `cancelled by the sender`; controller shows `0 of 1 sent / cancelled`; **display partials 0, controller spool 0** |
| Spool cleanup after a successful batch | **Pass** | `spool/` empty |
| 19.9 MB transfer completes and verifies | **Pass** | `received FILE (19910151B)`, ~7 s |
| **Save As a 19.9 MB file while interacting** | **Pass** | `/sdcard/Download/sample-app.apk` written at exactly 19,910,151 bytes; no ANR and no fatal exception in logcat; cached source intact; a swipe during the copy was accepted |
| **A presentation image does not become a received file** | **Pass** | 2.58 MB image: display log `received IMAGE (2582789B)`, landed in `presentation/`, `received/` payload count unchanged, zero `.jpg` in `received/` |
| Legacy cache migration on a real installation | **Pass** | the Lenovo's pre-existing `ready/` directory was classified at startup: two generic payloads with sidecars moved to `received/` and still listed, `presentation/` empty, `ready/` removed |
| Image and PDF presentation transfers still work | **Pass** | the image above rendered on the companion (`showing SHOWING_IMAGE`) |
| Received-file actions and 2x2 grid on API 24 | **Pass** | verified on screen and by the instrumentation suite running on that device |
| Pairing, discovery, role switching | **Pass** | the S22 was switched display -> controller and re-paired during this session |

**Not run on hardware, and why:**

- ~50 MiB boundary, aggregate batch-size boundary, and a full 20-file batch end to end. The
  20-file *dialog* is covered by instrumentation on both devices and the limits by unit tests, but
  no 20-file transfer was performed.
- Companion rejects the batch, and companion dismisses with Back. The reject path is unit-tested
  and shares `endBatch` with the cancel path that was verified, but neither was driven on the
  phones this round.
- Wi-Fi/hotspot disconnect mid-transfer, force-stop mid-transfer, and reconnect-then-send. The
  session-end teardown is code-reviewed and shares the same terminal path; **not** exercised.
- Transferring while screen mirroring is active, and mirroring survival afterwards.
- Landscape and large-font passes on the Lenovo. The bounded dialog and the action grid were
  designed for it and are instrumentation-tested at default settings on that device, but no
  large-font or landscape run was performed.
- Repeated cancel/send cycles beyond the single cycle verified above.
- Open and Share on a received file were verified in the previous round on the same code path and
  were not re-driven here.

### Not covered by unit tests

The controller-side terminal behaviour -- that cancellation sends `FILE_BATCH_CANCEL` before
cancelling the coroutine -- has **no unit test**. `ContentRouter` needs a live `RelaySession`, which
is a concrete class over a real `SecureConnection`, so a fake would mean standing up the loopback
harness. It is verified on hardware instead (three cancellation cases above, including cleanup of
both the partial and the spool). The receiving half of the same state machine *is* unit-tested in
full by `AcceptedBatchTest`.

### Two bugs the device found

Neither was reachable by inspection or by any unit test, and both are the reason this section
exists rather than a claim that the feature works.

**1. The FileProvider path never matched the cache layout.** `file_provider_paths.xml` declared
`path="ready/"`, which resolves to `<cacheDir>/ready/`, while `ContentCache` writes to
`<cacheDir>/relay_cache/ready/`. The first real `getUriForFile` call threw
`IllegalArgumentException: Failed to find configured root that contains ...` and took the app down
on the first tap of "Open". It had gone unnoticed because nothing exercised the provider until
these actions existed. Fixed by declaring the full path; `FileProviderPathTest` now fails if the
two drift apart again, and the intent helpers turn a provider misconfiguration into a message
rather than a crash.

**2. A transfer completion could overtake its own final chunks.** `TransferChunk` is `BULK` and
`TransferComplete` was `CONTROL`, and the session writer is strict priority: control before bulk.
For a large file the completion therefore jumped ahead of chunks still queued, and the receiver —
which requires chunks in order and checks the byte count before the digest — rejected a file that
was never corrupt. A 19.9 MB APK arrived as exactly 301 of 304 chunks (19,726,336 of 19,910,151
bytes). Small transfers never reproduce it, because `BULK` has no backlog to jump. Fixed by putting
`TransferComplete` on the same FIFO queue as the chunks, which is what makes "complete" mean "after
the bytes". `TransferCancel` deliberately stays on `CONTROL`, because cancelling is meant to
overtake the backlog.

Both failures were silent on the sending side, which reported only "the display could not verify
the file". The receiver now logs which rule was broken — byte count, digest, or storage — because
it is the only side that knows.

The end-to-end path **has** now been exercised on the two phones, which is what found both bugs
above. What remains unverified is listed under "Not exercised on hardware".


### Fault injection

`LoopbackSessionTest` uses `LoopbackPair`, a pair of `RelayLink`s joined by a bounded byte pipe.
It is not `PipedInputStream` on purpose: that class remembers the writing *thread* and fails once
that thread exits, which happens constantly with coroutine dispatchers.

Injected faults covered: disconnect during the handshake, corrupted authentication tag mid-stream,
plaintext command before the handshake, encrypted frame before any key, unsupported major
version, duplicate command id, slow reader / backpressure, wrong pinned identity, and a peer that
goes silent (heartbeat timeout, with an injected clock).

## Instrumentation tests

`RoleSelectionTest` covers first-launch chooser, role persistence across `Activity` recreation,
and both dashboards opening.

**These have not been run successfully yet.** The only available device is a Lenovo K33a42 with a
secure pattern lock, and `ActivityScenario` waits for `RESUMED`, which never arrives while a
secure keyguard is up. `RelayTestSupport.wakeAndUnlock()` handles a swipe-only lock screen but
cannot defeat a pattern.

To run them:

1. Unlock the phone, or temporarily set the screen lock to None/Swipe.
2. Keep the screen on (Developer options → Stay awake) so long suites do not stall.
3. `./gradlew connectedDebugAndroidTest`

Note: the Gradle task has reported `BUILD SUCCESSFUL` while the test APK failed to *install*
(`INSTALL_FAILED_ALREADY_EXISTS`). Always read the run output, not just the exit status. If that
happens, `adb uninstall com.avinash.relaydisplay` first.

## Two-device acceptance matrix

Nothing below has been executed: it needs two phones, and only one was attached. Record the date,
both build numbers and the result for each row.

| # | Scenario | Expected | Result |
| --- | --- | --- | --- |
| 1 | Same Wi-Fi, internet available | Pair, then QR appears on the display within a few seconds | |
| 2 | Same Wi-Fi, router's internet unplugged | Identical behaviour; nothing needs the internet | |
| 3 | S22 hotspot, Lenovo joined to it | Discovery or QR pairing works; relay works | |
| 4 | Lenovo hotspot, S22 joined to it | Works, or fails with a clear message if the OS isolates clients | |
| 5 | Background/foreground each app | No duplicate listeners or sessions; state unchanged | |
| 6 | Screen off, then unlock | Session survives, or reconnects within one ladder step | |
| 7 | Wi-Fi off then on | Reconnecting appears, then Connected, with no manual step | |
| 8 | Hotspot restarted (new endpoint) | Fast path fails quickly, discovery finds the new endpoint | |
| 9 | Force-stop the controller, reopen | One tap to reconnect; pairing intact | |
| 10 | Force-stop the display, reopen | Controller shows Reconnecting, recovers when the display is available | |
| 11 | Static QR shown for 30 minutes | Near-zero CPU; battery drain comparable to an idle screen-on device | |
| 12 | Repeated transfers, small and near 50 MB | All verify; no OOM on the Lenovo; cache stays bounded | |
| 13 | Cancel a transfer mid-flight | Partial file deleted on the display; session still usable | |
| 14 | Mirror for 30 minutes | Note heat, battery, dropped-frame count, and whether it survives a reconnect | |
| 15 | Mirror an app that sets FLAG_SECURE | Black region, and the app says why | |
| 16 | Pair, then Forget device on one side | Next connection demands the six-digit code again on both | |
| 17 | Deliberately confirm a *mismatched* code | Trust is not written; both sides end the session | |

## Manual security checks

Not automated; verify by hand and record.

- [ ] Recents screenshot of the display shows no sensitive content when the privacy setting is on
- [ ] The ongoing notification names no peer and no content
- [ ] Stopping the mirror removes the system capture indicator immediately
- [ ] Revoking screen capture from the system UI tears down the encoder and notifies the peer
- [ ] Denying the local network permission on API 37 produces the blocked state, not a retry loop
- [ ] Denying camera twice shows the settings route, not another dialog
- [ ] `adb shell dumpsys package com.avinash.relaydisplay` shows only `MainActivity` exported
- [ ] An exported diagnostics report contains no address, token, filename or payload

## Power measurement

```bash
adb shell dumpsys batterystats --reset
# run the scenario for a measured interval
adb shell dumpsys batterystats com.avinash.relaydisplay > battery.txt
adb shell dumpsys cpuinfo | grep relaydisplay
```

Record four states: display waiting; connected with a static QR; active image transfer; mirroring.
The first two should show effectively no CPU after the initial render — that is the design intent
and the number worth defending in future changes.

## Regression run for the presentation-state work

Reproduction evidence and the correlated two-device timeline are in `diagnostics/FINDINGS.md`.
Raw captures are in `diagnostics/logs/` (gitignored; they contain device-specific addresses).

### Device availability

| Device | Attached over | State | Consequence |
| --- | --- | --- | --- |
| Galaxy S22 Ultra | Wi-Fi debugging (`adb-<serial>-<token>._adb-tls-connect._tcp`) | unlocked | full instrumentation suite runs |
| Lenovo K33a42 | USB | unlocked | full instrumentation suite runs |

Both phones now run the suite. Earlier rounds recorded the Lenovo as blocked by a pattern lock;
that was a property of how the phone was left, not of the phone.

Wi-Fi debugging is what unblocked the S22 after its USB cable dropped. Serials are deliberately
not recorded in this repo -- they are stable hardware identifiers -- so read them off whichever
machine you are on:

```bash
adb devices -l          # note the adb-<serial>-<token>._adb-tls-connect._tcp name
ANDROID_SERIAL='<the name adb printed>' ./gradlew connectedDebugAndroidTest
```

> **The instrumentation suite is destructive to real app state.**
> `RoleSelectionTest` and `SettingsFlowTest` call `RelayTestSupport.resetPersistedState()` in
> `@Before`, which clears the device role, the operating mode **and the trusted peer**. They need
> a genuine first-launch state to be meaningful, so this is by design — but it means running the
> suite on a phone you actually use will **un-pair it** and you will have to pair again.
>
> A failed run can also uninstall the app: the Gradle runner installs without `-r`, so an
> existing install produces `INSTALL_FAILED_ALREADY_EXISTS` and can leave the package removed.
> Note also that the task can print `BUILD SUCCESSFUL` while that install failed — always read
> the run output, not the exit status.
>
> Pair again afterwards, or use a spare device.

### Instrumentation results

**31 tests, 0 failures on both devices** — S22 (Android 16 / API 36) and Lenovo K33a42
(Android 7.0 / API 24). API 24 is the interesting half: it is where a `NewApi` slip or a class
that fails ART verification actually shows up.

| Suite | Tests | Covers |
| --- | ---: | --- |
| `PresentationToolbarTest` | 10 | Copy and Close present for text; TalkBack labels; 48dp targets; Close fires once; Back closes; exact multiline Unicode copied to clipboard; QR payload copyable; Copy absent for image and mirror; empty text safe |
| `DashboardChromeTest` | 8 | the gear sits in the top bar and opens Settings; Back is in the start slot on detail screens; touch targets; both dashboards render their chrome |
| `SettingsFlowTest` | 7 | role change needs confirmation, mode reset, mode survives recreation, Pause stops everything, Forget peer, **Back after a role change shows the new role**, **the rebased stack survives recreation** |
| `RoleSelectionTest` | 5 | first-launch chooser, role persists across Activity recreation, both dashboards |
| `ExampleInstrumentedTest` | 1 | package context |

#### Three production bugs this suite caught

All three were real defects, not test flakiness, and all three are fixed:

1. **`NullPointerException` in `Screen.fromRoute` on Activity recreation.** The companion held an
   eagerly built `listOf(RoleChooser, ...)`. `fromRoute` is the first thing touched when
   `rememberSaveable` restores the back stack, and class-initialisation order could leave entries
   null — `Attempt to invoke virtual method 'String Screen.getRoute()' on a null object
   reference`. Replaced with a `when`, which has no initialisation order to get wrong. Pinned by
   `ScreenRouteTest`, which deliberately uses string literals so referencing a screen object does
   not mask the ordering.

2. **Back after a role change showed the old role.** Reported from the device. `reconcileWithRole`
   only inspected the *current* screen, so changing the role from Settings left the stack as
   `[ControllerHome, Settings]` with the outgoing role's dashboard still at the root. The role in
   storage was correct; the navigation stack was not. Replaced by `RelayNavigator.rebaseForRole`,
   which rebuilds the whole stack: the new role's home becomes the root, and screens above it
   survive only if they are role-agnostic or belong to the new role. Pinned by nine
   `RoleNavigationTest` cases and two on-device tests.

3. **Availability-mode rows were not clickable.** The test tag sat on a `Row` with no click
   handler; only the ~20dp radio dot responded. Both the mode rows and the fit-mode rows are now
   `selectable` across their full width with `Role.RadioButton` — a real accessibility and
   touch-target fix on a cracked screen, not just a test accommodation.

### Two-device regression matrix

**20 of 25 rows now executed on hardware** (19 pass, 1 failed and is fixed but not re-verified), S22 Ultra (Android 16) against Lenovo K33a42
(Android 7.0), over Wi-Fi on one subnet. Four bugs came out of it, all four described below.
Nothing is marked passed without a real run.

| # | Scenario | Result |
| --- | --- | --- |
| 1 | Pair normally | **pass** — over discovery, the six-digit code matched on both |
| 2 | Send text, close locally on the Display | **pass** |
| 3 | Send text, close remotely from the Controller | **pass** |
| 4 | Copy short text | **pass** |
| 5 | Copy multiline Unicode text | **pass** — Latin/CJK/Cyrillic/emoji and line breaks intact end to end |
| 6 | Text A then immediately Text B | **pass** — B wins and stays won |
| 7 | Text then QR | **pass** |
| 8 | Text then image | not run |
| 9 | Text then start mirroring | **pass** — 4.9 MB delivered during a scroll burst |
| 10 | Stop mirroring locally | not run |
| 11 | Stop mirroring remotely | **fail, fixed, not re-verified** — see below |
| 12 | Back on the Display during text | **pass** — closes content, keeps the session |
| 13 | Back on the Controller during sending | not run |
| 14 | Disconnect while text is visible | **pass** |
| 15 | Reconnect with the waiting screen up | **pass** |
| 16 | Controller to Display while connected | **pass** |
| 17 | Change back to Controller | **pass** — both directions exercised on both phones |
| 18 | Display to Controller while connected | **pass** — found a bug, see below |
| 19 | Both devices swapped | **pass** — reconnects on stored trust, no re-pairing |
| 20 | Force-stop and reopen after a role switch | **pass** |
| 21 | Pause while connected | **pass** — found a bug, see below |
| 22 | Resume On Demand and reconnect | **pass** |
| 23 | Restart the hotspot and reconnect | not run |
| 24 | 20x send/close/copy for races | **pass** — 20 cycles racing both writers, phones still agree |
| 25 | About & Legal on both sizes and themes | not run |

### What *was* verified on hardware this round

| Check | Device | Result |
| --- | --- | --- |
| Debug APK installs | both | pass |
| App launches, no `FATAL EXCEPTION` | Lenovo (API 24) | pass |
| No StrictMode violations at startup | Lenovo | pass |
| Foreground service typed `connectedDevice` | both (earlier capture) | pass |
| Release APK contains `assets/third_party_licenses.txt` | build output | pass |
| Full instrumentation suite | both | pass (31/31) |
| Role change Controller -> Companion display, via Settings | Lenovo | pass |
| Display binds a listener and mints a pairing code | Lenovo | pass, port 55737 under the app's uid |
| Display advertises `_relaydisplay._tcp` | Lenovo, browsed from the Mac | pass, instance `Lenovo K33a42` |
| Controller browse finds that advertisement | S22 | **not confirmed** -- see below |

The advertisement is real and answerable from a third machine:

```bash
dns-sd -B _relaydisplay._tcp local
# 23:35:05.280  Add  2  14  local.  _relaydisplay._tcp.  Lenovo K33a42
```

The controller's own list stayed empty for the ~30 s it was observed. That is **not** yet a
finding: the phone was picked up mid-observation, so the pairing screen -- and with it the browse
-- may not have been in the foreground for that whole window. Re-run with the controller left
alone before drawing any conclusion.

### What the first real two-device run found

Rows 2 and 4 passed, and a connection was established in both directions -- S22 "Connected to
Lenovo K33a42", Lenovo "Connected to samsung SM-S908E".

**Row 2** is the whole presentation-state round in one gesture. Closing on the Display returned it
to its own dashboard while the session stayed up, and the Controller's status line changed from
"Showing on the display." to **"The display closed it."** -- learned from the Display's report, not
from the Controller's own belief.

**Row 4** was verified across devices rather than trusted: after tapping Copy on the Display, the
clipboard was pasted into a local text field and held exactly `Matrix row 2 - close on display`,
the text that had been received. (The unsaved field was then discarded, and the device name was
confirmed unchanged.)

Also observed: navigating to Settings on the Display and back does not drop the session.

#### The fourth bug this run found

**Closing the mirror on the Display did not stop the Controller capturing.** After the Display
closed the mirror presentation and returned to its waiting screen, the Controller still said
"Sharing at 596x1280, 24 fps", and the system still listed a live capture:

```
dumpsys media_projection
Media Projection:
(com.avinash.relaydisplay, uid=10893): TYPE_SCREEN_CAPTURE
```

The phone went on capturing its owner's screen, recording indicator lit, for a viewer that had
closed it. The Display's report reached `RemotePresentationTracker` correctly — nothing connected
that report to the encoder.

Fixed in `ContentRouter`: any accepted phase report that is not `MIRRORING` stops a local capture.
The trigger is deliberately broader than a dismissal — the Display shows one thing at a time, so
sending it text also ends the mirror, and capturing for something nobody is watching is the same
problem either way. `MirrorController.stop` already invokes the callback that stops the foreground
service and releases the projection.

> **Not re-verified on hardware.** The fix is built, installed on both phones, unit tests and lint
> pass — but the controller phone was picked up before the confirming run, so row 11 stays marked
> failed. Re-run it before believing this one.

#### How row 9 nearly became a false alarm

While mirroring was live, the Display's whole Wi-Fi interface received **78 bytes in 8 seconds**,
which read exactly like "the Controller claims to be sharing and nothing is being sent". It was
not a bug: the Controller's screen was sitting still on a static page, and an H.264 encoder fed by
a `VirtualDisplay` correctly produces nothing when nothing changes. Driving the Controller's
screen through a scroll burst and re-measuring gave **4.9 MB**.

Two lessons worth keeping: a `SurfaceView` screenshots as pure black because `screencap` does not
compose hardware video layers, so a screenshot can neither confirm nor deny mirroring; and
throughput must be measured while the source is actually moving.

#### The third bug this run found

**A role change away from Display hung, and the engine never restarted in the new role.**
Switching the connected Display to Controller left the Lenovo showing "Waiting for the other
phone" with a Cancel button — a Display state on a Controller dashboard — and it stayed there.
It never connected again, even with the other phone advertising and visible to a third machine.

Its diagnostics log gave the cause in one line:

```
00:23:18  I  RD/RoleSwitch: complete -> CONTROLLER
00:23:18  W  RD/RoleSwitch: shutdown timed out; continuing
00:23:14  I  engine: stopping: role change
```

Four seconds between `stopping` and `complete` is exactly `RoleSwitchCoordinator.SHUTDOWN_TIMEOUT_MS`,
and nothing at all was logged afterwards. `/proc/net/tcp6` still showed a listening socket under
the app's uid.

`RelayEngine.stop()` cancels the run loop, then "closes what it may be blocked on", then joins.
But the display cycle spends nearly all its life inside `ServerSocket.accept()`, and the one thing
`closeEverything()` did **not** close was the listener — it closed the advertiser, the browse and
the active session. Coroutine cancellation cannot interrupt a blocking `accept()`; only closing
the socket can. So `join()` waited, the role switch gave up after its timeout, and the old role's
listener stayed bound with the engine never coming back up.

Fixed by holding the bound listener in an `AtomicReference` that `closeEverything()` closes first,
rather than relying on the `finally` of a coroutine that cannot resume.

Verified on hardware: the same switch now logs `begin -> CONTROLLER (connected=true)` and
`complete -> CONTROLLER` **in the same second**, with no timeout warning, and both phones land in
correct states. `ListenerShutdownTest` pins the contract `stop()` depends on — that closing a
listener unblocks a thread parked in `accept()` within seconds rather than never.

> **Coverage gap, stated plainly:** the *wiring* (that `closeEverything` closes `activeListener`)
> is verified on hardware and by reading the log, not by a unit test. `RelayEngine` takes
> `NsdBrowser`, `NsdAdvertiser` and `AndroidPlatformCapabilities` directly, so it cannot be
> constructed on the JVM. Making it constructible would need interfaces for those three.

#### The second bug this run found

**A Display went on naming a peer that had hung up.** Pausing the Controller tore the session
down; the Controller correctly went to Paused and its socket closed, but the Display's screen kept
saying "Connected to samsung SM-S908E" indefinitely -- observed for over a minute, well past the
45 s dead-peer threshold.

Not a network problem. `/proc/net/tcp6` on the Display showed only its listening socket under the
app's own uid: the connection really was gone. The app's own diagnostics log named the cause:

```
00:10:57  I  RD/Session: session ended
00:09:42  D  session: Connected -> Connected      <- the only transition around a whole session
00:09:42  I  RD/Session: session started c57d6c role=DISPLAY
00:09:11  I  RD/Session: session ended
```

`session ended` twice, and no state transition after either. `RelayEngine.runDisplayCycle` accepts
in a loop: when `serveOne` returned it went straight back to `accept()` without dispatching
anything, so `SessionCoordinator` never left `Connected`. The next session's `TransportConnected`
and `PairingProgress` were then swallowed by the reducer's `Connected` branch — deliberately, so a
stale pairing event cannot knock a live session out — which is the `Connected -> Connected`
self-transition above.

Fixed with an explicit `ConnectionEvent.PeerDisconnected`, dispatched by the accept loop before it
blocks again. It is deliberately not `LinkLost`: link loss means the connection this device wanted
is gone and it should reconnect or fail, whereas a Display whose listener is still bound has
nothing to retry — it is waiting for the next caller. From `Connected` it reduces to
`Pairing(AwaitingPeer)`; from anything else it is a no-op, and it cannot wake a paused device.

Verified on hardware: the Display now flips to "Waiting for the other phone" within ~5 s of the
Controller pausing. Pinned by four `ConnectionStateMachineTest` cases, including one that walks a
second caller through the same transitions as the first.

#### The first bug this run found

**"Displays on this network" could never populate.** The only `browser.start` in the codebase was
inside `RelayEngine.discoverOne`, which is part of the *connect* flow, and its `finally` stopped
discovery as soon as an endpoint was found. So the pairing screen's list was filled only during a
connection attempt on a phone that was already connecting -- which is exactly when nobody is
reading it. On a Controller sitting on the pairing screen with nothing paired, no attempt is
running, so the section was a promise the app could not keep, and there was no affordance on that
screen to start a search either.

Diagnosed by elimination, not by guessing: the Display was confirmed to be listening
(`/proc/net/tcp6`, port under the app's own uid) and advertising (`dns-sd -B _relaydisplay._tcp
local` from a third machine on the same subnet saw the instance immediately), while the Controller
sat on the pairing screen for ~20 s with the accessibility tree confirming the screen was
foreground the whole time.

Fixed by refcounting one browse between its two consumers -- a connect attempt wants the first
compatible endpoint and then stops; the pairing screen wants a live list for as long as it is
open. `NsdBrowser` holds a single listener, so two independent browses were never an option.
`RelayEngine.retainBrowse` / `releaseBrowse` are balanced by a `DisposableEffect` on the pairing
screen, so mDNS and its multicast lock stop the moment the screen goes away. A browse that fails
to start now surfaces on that screen instead of being swallowed.

#### Why the run stopped

The controller phone has a two-minute screen timeout, a secure lock and no charger, so it locks
between steps and `ActivityScenario`-free adb driving stops with it. The remaining rows need it
unlocked and awake.

### Phase 1 / Phase 2A verification (single device)

Run on the S22 Ultra (Android 16 / API 36) over wireless debugging. **The Lenovo was off-network,
so nothing two-device was run** -- no mirroring soak, no matrix.

| Check | Result |
| --- | --- |
| `testDebugUnitTest` | 339 tests, 0 failures (was 331) |
| `lintDebug` | 0 errors |
| `assembleDebug` / `assembleRelease` | both succeed |
| `connectedDebugAndroidTest` on S22 | **31 tests, 0 failures** -- read from the result XML, not the BUILD line |
| App licence screen renders the GPL notice | pass -- copyright, grant, warranty disclaimer and where to get a copy all present |
| Source link opens the repository | pass -- tapping it left RelayDisplay and opened `avina5hkr / relay-display` |
| Legal asset survives R8 | pass -- `assets/third_party_licenses.txt`, 25,267 bytes, readable from the release APK |
| `META-INF/LICENSE.txt` now packaged | pass -- 12,484 bytes, previously excluded |

The instrumentation suite passing after the queue split is a regression check, not a mirroring
test: none of those 31 tests exercise a live session between two phones.

### Two-device reproduction of the mirror failure (baseline 0bfb9e5)

Run on the actual pair: S22 Ultra (Android 16) as Controller, Lenovo K33a42 (Android 7.0) as
Display, same Wi-Fi subnet, paired fresh by comparing the six-digit code. Both phones ran a debug
APK built from **0bfb9e5**, i.e. before the queue split, so the failure could be observed as
reported.

#### It does not fail while the app is in the foreground

| Condition | Result |
| --- | --- |
| App foregrounded, screen scrolling continuously | **healthy for 276 s**, ~500 KB/s sustained at the Display, MediaProjection active throughout |

Pings land at 15, 30, 45, 60 s and every one survived. **The control-starvation theory does not
explain the reported symptom.** On a fast local link the writer keeps up, the shared queue rarely
fills, and the heartbeat never fails to enqueue.

#### It fails within ~30 s of backgrounding the Controller app

Backgrounding RelayDisplay and opening another app (Settings, then Gallery) reproduced it
immediately:

```
03:21:23  mirroring started, projection=1
          ... 276 s healthy, ~500 KB/s to the Display ...
03:27:29  RelayDisplay backgrounded, Settings opened
          throughput halves: 500 KB/s -> ~280 KB/s
03:28:10  Display: "RD/Session: session ended", Connected -> Reconnecting(#1)
03:28:29  Display established sockets = 0, listening = 1
          Controller MediaProjection = 1   <-- still capturing
```

#### What the Controller was doing four minutes later

```
03:31:56  D  mirror: dropped 11520 frames to keep up
03:31:56  D  session: Connecting -> Reconnecting(#12)
          UI: "Reconnecting (attempt 11). The other phone did not answer."
          dumpsys media_projection: com.avinash.relaydisplay TYPE_SCREEN_CAPTURE
```

The session died at 03:28:10. At 03:31:56 the Controller was still holding `MediaProjection`, still
running the encoder, and still incrementing a dropped-frame counter for a peer that had been gone
for nearly four minutes. The capture indicator stayed lit the whole time, and the screen being
captured was the user's photo gallery.

**This is defect B, not defect A.** `MirrorController` captures `engine.activeSession.value` once
at start; when that session closes, nothing stops the encoder, releases the `VirtualDisplay` or
tears down the projection. Reconnect then builds a *new* session that the running capture is not
attached to, which is why the Display never resumes and the Controller never notices.

The Display half behaved correctly: it detected the drop, showed the frozen last frame with
"Controller disconnected", closed cleanly and went back to listening.

#### A diagnosability defect found on the way

`mirror: dropped N frames to keep up` is emitted roughly once a second while a mirror is failing.
The diagnostics log is bounded, so those messages evicted the original session-close reason before
it could be read. A log that floods itself during the exact failure it exists to explain is not
much use; the drop counter belongs in a metric, not in the event log.

### Still not verified on hardware

The one-minute mirror failure was diagnosed from code and fixed, and the fix is covered by tests
over a real encrypted loopback session. It has **not** been reproduced or confirmed fixed on the
phones, because that needs the Companion Display attached at the same time as the Controller.

### Visual defects found by reading screenshots

Three rounds now, the same method has found things no assertion did. Screenshots are taken with
`adb exec-out screencap -p` and read directly.

| Defect | Cause | Fix |
| --- | --- | --- |
| Disabled tiles invisible | `surfaceVariant` tile on a `surfaceVariant` page | `surface` fill plus a 1dp `outlineVariant` border |
| Band of dead space above every title | Scaffold and the top bar both applying `statusBars` | top bars apply only `WindowInsets.displayCutout` |
| The link glyph was an unreadable blob | two whole rounded rects overlapping across the middle third, outlines crossing | two open half-capsules bridged by a bar |
| The status hero looked like every other card | both filled `surfaceVariant`, so "Not connected" had no visual weight | plain cards are outlined on the page background; the tonal fill now belongs to `ConnectionHero` alone |

A note on capture timing: on the Lenovo a screenshot taken immediately after a tap caught a
half-composed screen -- the top bar and hero painted, the three sections below still blank. That
is a slow device, not a bug. Dump the accessibility tree first and only screenshot once the
expected nodes are present.
