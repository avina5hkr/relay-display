# RelayDisplay architecture

One Gradle module (`:app`), Kotlin only, Compose Material 3, minSdk 23 / target 37.

## Why a single module

The app is roughly 60 source files with one obvious dependency direction. Splitting it into
`:core`, `:network`, `:ui` modules would add build configuration and a public/internal boundary
problem without removing any real coupling. If a second app target ever appears, the split lines
are already visible in the package structure below.

## Package map

```
com.avinash.relaydisplay
  app/          Application, dependency container, device naming
  data/settings Preferences DataStore repository and the settings snapshot
  data/peers    Trusted-peer persistence
  domain/model  Role, mode, fit, rotation, content kind, trusted peer + its codec
  protocol/     Frame codec, TLV body codec, message types, typed messages, limits
  security/     HKDF, AES-GCM record ciphers, EC identity, handshake, pairing URI
  network/session   State machine, backoff, coordinator, engine, session, handshake runner
  network/transport TCP + loopback links, secure connection
  network/discovery NSD advertise / browse
  platform/     Permission policy, capability queries, connectivity monitor
  content/      QR encode, URL + filename validation, cache, transfer, decode, routing
  mirroring/    Profile negotiation, screen encoder, video decoder, controller
  service/      Connection foreground service, projection foreground service, notifications
  diagnostics/  Bounded in-memory event log
  ui/           Navigation, screens and view models per role
```

## Ownership and lifetimes

| Object | Lifetime | Owns |
| --- | --- | --- |
| `AppContainer` | process | every long-lived object below |
| `SettingsRepository` | process | Preferences DataStore |
| `TrustedPeerRepository` | process | the one trusted peer record |
| `SessionCoordinator` | process | `ConnectionState` — the only writer |
| `RelayEngine` | process | listener, dialer, NSD, the active `RelaySession` |
| `RelaySession` | one connection | socket, ciphers, reader/writer/heartbeat coroutines |
| `ContentRouter` | process | inbound/outbound transfers, message routing |
| `PresentationController` | process | **authoritative** presentation state on the Display |
| `RemotePresentationTracker` | process | the Controller's copy of the Display's reported state |
| `RoleSwitchCoordinator` | process | serializes role changes; owns the teardown order |
| `MirrorController` | process | encoder (controller) or decoder (display) |
| `RelayConnectionService` | while a session is wanted | the ongoing notification |
| `MirrorProjectionService` | while capturing | the `MediaProjection` |
| view models | one screen | nothing but derived state |

Nothing holds an `Activity`. The only `Context` stored anywhere is the application context.

## State flow

```
        settings (DataStore)
                |
                v
SessionCoordinator  <-- ConnectionEvent -- RelayEngine -- sockets, NSD
        |                                       |
        | ConnectionState (StateFlow)           | RelayMessage (SharedFlow)
        v                                       v
   view models                            ContentRouter
        |                                       |
        v                                       v
     screens                          PresentationController / transfers
```

`ConnectionStateMachine.reduce` is a pure function, so every transition is unit tested. The
coordinator is the only caller, which is what guarantees a single writer.

## Concurrency rules

- All network I/O runs on `Dispatchers.IO`; blocking reads are fine there and cancellation closes
  the socket, which unblocks them.
- A `RelaySession` has exactly one reader coroutine. The receive cipher's strict sequence check
  depends on that.
- Writes go through a `Channel` drained by one writer coroutine, so records are never interleaved
  and the send cipher's sequence stays monotonic.
- Every `Closeable` is closed in the `finally` of the function that opened it. Every `stop` is
  idempotent.

## API-level strategy

`PermissionPolicy` holds every permission decision as a pure function of `sdkInt`, tested at API
23, 33, 34, 36 and 37. Platform *call sites* still spell out `Build.VERSION.SDK_INT >= N` inline,
because an indirect check is invisible to both lint and ART's class verifier — a method whose body
references `NotificationChannel` can fail verification on API 23 even if it is never called.

## Dependency choices

| Dependency | Why | Licence |
| --- | --- | --- |
| AndroidX Core / Activity / Lifecycle / Compose | platform UI and lifecycle | Apache 2.0 |
| AndroidX DataStore Preferences | the recommended replacement for SharedPreferences; `datastore-preferences-core` makes the repository unit-testable on the JVM | Apache 2.0 |
| AndroidX CameraX | the maintained camera API; minSdk 21 | Apache 2.0 |
| AndroidX ExifInterface | photo orientation without decoding the image | Apache 2.0 |
| kotlinx-coroutines | structured concurrency and flows | Apache 2.0 |
| ZXing Core 3.5.4 | QR encode and decode; pure Java, no Android or network dependency, so it is unit-testable | Apache 2.0 |
| desugar_jdk_libs 2.1.5 | backported Java APIs for minSdk 23 | GPL v2 + Classpath Exception (build-time) |

### Deliberately not used

- **androidx.navigation** — requires minSdk 24, and the companion device must keep working on
  API 23-class hardware. Replaced by `RelayNavigator`, a ~50-line saveable back stack.
- **kotlinx.serialization** — would add a compiler plugin for a protocol that needs a
  deterministic *binary* encoding anyway. The hand-written TLV codec is smaller, canonical by
  construction, and directly golden-testable.
- **Any DI framework** — the graph is one container with a dozen fields.
- **Firebase, analytics, crash reporting, ads, accounts** — the app has no server side at all.

## Performance decisions

- QR codes are drawn as whole-pixel rectangles on a Canvas, never as a scaled bitmap, because
  interpolated module edges are the main cause of failed scans.
- Images are decoded with `BitmapFactory` bounds-first and an integer sample size, targeting the
  actual view size. `ImageDecoder` is avoided: it needs API 28 and buys nothing for still images.
- PDF pages are rasterised one at a time and the renderer is closed between pages, so a document
  left on screen for an hour pins no file descriptor.
- Mirroring goes compositor → encoder surface → decoder → `SurfaceView`. No frame ever enters the
  app heap on either side.
- The waiting screen is static: no animation, no ticking clock, no progress spinner unless
  something is genuinely in flight.

## Authoritative state ownership

There are exactly two runtime state owners, and no Compose screen invents either.

| State | Owner | Who may write it |
| --- | --- | --- |
| Connection state | `SessionCoordinator` | only `SessionCoordinator.dispatch` |
| Presentation state (Display) | `PresentationController` | only its own methods, all revision-checked |
| Presentation state (Controller's view) | `RemotePresentationTracker` | only a `PRESENTATION_STATE` report from the Display |
| Role transition | `RoleSwitchCoordinator` | one caller at a time, `Mutex.tryLock` |

The rule that fixes the reported bug: **the Controller never writes its own belief about the
remote screen.** Sending content sets `SENDING`/`SENT`; only a report from the Display can reach
`DISPLAYED`, and only a newer report can leave it.

`RemoteContentStatus` distinguishes Sending, Sent, Displayed, DisplayClosed, Disconnected and
Failed, because "I put it on the wire" and "the other phone drew it" are genuinely different
facts and the old UI conflated them.

### Generic file transfer

Separated along the lines the feature actually has, so no single class owns both a stream and a
policy decision:

| Concern | Where |
| --- | --- |
| Limits, metadata and batch validation, executable warning | `content/FileTransferPolicy.kt` (pure, no Android) |
| Batch and per-file phases | `content/FileBatchState.kt` (pure, no Android) |
| An offered batch awaiting a decision | `content/IncomingBatch.kt` (pure, no Android) |
| The approved manifest and per-file states | `content/AcceptedBatch.kt` (pure, no Android) |
| Reading a source once, into app-private scratch | `content/SourceSpool.kt` |
| Copying a received file out, off the main thread | `content/FileExport.kt` |
| A stored, verified file and its metadata sidecar | `content/ReceivedFiles.kt` |
| Untrusted filenames | `content/FilenameSanitizer` in `UrlValidation.kt` |
| Spooling, measuring and digesting before offering | `ContentSource.spoolTo()` in `content/SourceSpool.kt` |
| Sending the prepared bytes | `SpooledContentSource` in `content/ContentSource.kt` |
| Reading a picked file through the resolver | `UriContentSource.forGenericFile()` |
| Batch driving: offer, await consent, stream in order | `content/ContentRouter.kt` (`sendFileBatch`, `runBatch`) |
| Receiver stream lifecycle, digest, atomic promote | `content/TransferReceiver.kt` (existing, extended) |
| Temporary and promoted file storage, retention | `content/ContentCache.kt` (existing, extended) |
| Protocol serialisation, including the batch manifest | `protocol/MessageCodec.kt` (existing, extended) |
| Handing a file to another app | `FileProvider`, scoped by `res/xml/file_provider_paths.xml` |
| Picker, review list, batch progress | `ui/controller/FileSendSection.kt`, used by both `ControllerHomeScreen` and `SendScreen` |
| Incoming prompt, received-files list and actions | `ui/display/ReceivedFiles.kt` + `DisplayViewModel` |

`FileTransferPolicy`, `FileBatchState` and `IncomingBatch` have no Android dependency at all,
which is why the security-critical rules run in milliseconds on the JVM instead of needing a
device.

**One file-sending UI, two entry points.** The dashboard's **Files** tile and the Send screen's
Files section both render `FileSendSection` and both resolve the same `SendViewModel` (keyed
`"send"`), so there is one batch and one place its state lives. The tile picks straight into the
system picker and shows the review list in place rather than navigating first — the same reasoning
the Image and PDF tiles already followed, since the next thing the user sees is a system picker
either way. `SendFocus` was deliberately *not* extended with a `FILES` value: it describes the text
composer (draft label, "Show as text"), so a file entry in it would have forced meaningless labels
and added a bogus button to the composer's list of alternatives.

**Consent is a state machine, not a flag.** `AcceptedBatch` holds the manifest the user approved,
keyed by transfer id, with an explicit state per entry, and every incoming generic offer is matched
against it before a partial file exists. It replaced a `batchAccepted` boolean, an
`acceptedBatchId` that was assigned and never read, and two counters -- a shape that could not
answer "is this one of the files I agreed to?". Being pure Kotlin, the whole matching policy is
unit-tested in milliseconds.

**One terminal path per batch, on each side.** `ContentRouter.endBatch` on the receiving side and
`finishOutbound` on the sending side are the only places a batch ends, and both are safe to call
repeatedly. Everything funnels through them: rejection, local expiry, a peer cancel, completion,
and session teardown. Scattering that cleanup was how the old code left consent set after a
cancellation. Cancellation notifies the peer *before* cancelling the coroutine, because
`streamOneFile` rethrows `CancellationException` before it could send anything.

**Files are read once and sent from a spool.** `SourceSpool` gives each outbound file an
app-private copy, and the manifest describes that copy. A `ContentResolver` makes no promise that
two reads of the same URI agree, and the transmit path used to be that second read. Spool files are
never exposed through the FileProvider and are swept at startup as well as on every terminal path.

**Cache directories are separated by purpose**, not by whether a metadata sidecar happened to be
written: `received/` for generic user files (the only directory the FileProvider exposes),
`presentation/` for images and PDFs, `incoming/` for partials, `spool/` for outbound copies. They
shared one `ready/` directory before, and the received-files list enumerated all of it, so an image
sent last week reappeared as a UUID with `application/octet-stream`. `ContentCache.migrateLegacyLayout()`
classifies an existing installation once, at startup: a payload with a valid sidecar was a generic
file and moves to `received/`, anything else was a presentation payload and moves to
`presentation/`. Nothing is deleted for lacking metadata, and the migration is idempotent.

**One streaming implementation, two callers.** `ContentRouter.streamOneFile` owns the whole
per-file protocol exchange, and both the presentation path (`sendFile`, for images and PDFs) and
the batch path (`runBatch`) go through it. They differ only in what they show the user, which is
what its callbacks are for. Duplicating the chunk loop would mean fixing every streaming bug
twice.

**The batch wraps the per-file exchange rather than replacing it.** A batch adds one confirmation
in front; each file then streams through the `CONTENT_OFFER / TRANSFER_*` machinery that already
existed and was already tested. That is also why sending is strictly sequential:
`ContentLimits.MAX_CONCURRENT_TRANSFERS` is 1 and the receiver enforces it by rejecting a second
offer with `BUSY`, so concurrency here would produce rejections rather than speed.

File chunks travel as `TransferChunk` on the existing `TrafficClass.BULK` queue — backpressured
rather than lossy, and ranked below `CONTROL` — so a transfer cannot starve heartbeats the way
video once did. Batch negotiation, accept, reject, cancel and acknowledgement stay on `CONTROL`.
No new socket and no plaintext path was added.

**Layering note.** `ContentLimits.MAX_FILES_PER_BATCH` lives in the protocol package, not in
`FileTransferPolicy`, because the decoder needs it to bound a batch manifest before parsing any of
it. `FileTransferPolicy` re-exports it, exactly as it already does for `MAX_FILE_BYTES`. Putting
it the other way round would have made `protocol` depend on `content`, which is backwards.

### Outbound traffic classes

One connection carries video, control and bulk transfer. They must not share a queue.

| Class | Queue | Full behaviour | Carries |
| --- | ---: | --- | --- |
| `CONTROL` | 32 | close the session | Ping/Pong, Bye, Ack, errors, presentation state and commands, text/QR/link, **all mirror negotiation and teardown** |
| `BULK` | 8 | suspend the sender | transfer chunks |
| `MEDIA` | 3 | drop oldest | encoded video frames only |

One writer drains them with strict priority for `CONTROL`, then alternates `BULK` and `MEDIA` so
neither starves the other. Classification lives in `RelayMessage.trafficClass()` so a new message
type has to choose, and the default for anything unclassified is `CONTROL` — the safe failure is
to deliver reliably, not to drop.

**Why this exists.** Every message used to share one 8-slot channel. `MirrorController` submits
~24 frames a second, so the queue was routinely full, and `heartbeatLoop` responded to a `Ping`
that would not enqueue by closing the whole session with "outbound queue full". Pings land at 15,
30, 45 and 60 seconds; each was a fresh chance to kill a healthy mirror. That is the mechanism
behind mirroring stopping about a minute after it started.

The heartbeat still closes on a full `CONTROL` queue, because that genuinely means the writer is
wedged — video and transfers can no longer fill it.

### Stopping is the hard part

Three of the four bugs found in the first real two-device run were the same shape: something
stopped, and nobody told the other half.

- **A blocking `accept()` is not cancellable.** `RelayEngine.stop()` cancels the run loop and then
  joins it, but the display cycle lives inside `ServerSocket.accept()`, which coroutine
  cancellation cannot interrupt. The engine holds the bound listener in an `AtomicReference` so
  `closeEverything()` can close it from outside; without that, `join()` never returned and a role
  change timed out with the old role's listener still bound.
- **A peer going away is not a link loss.** A Display that keeps listening has nothing to
  reconnect to, so its accept loop dispatches `PeerDisconnected` — `Connected` becomes
  `Pairing(AwaitingPeer)` — rather than `LinkLost`, which would send it into the reconnect ladder.
- **A capture must not outlive its viewer.** Any phase report from the Display that is not
  `MIRRORING` stops a local capture, so closing the mirror on the receiving phone also releases
  the `MediaProjection` and its recording indicator on the sending one.

### The SessionHost seam

`ContentRouter` and `MirrorController` depend on `SessionHost` (`activeSession` + `messages`)
rather than the concrete `RelayEngine`. That keeps both testable without a socket and stops
either reaching into engine internals.

### Navigation

`RelayNavigator` is a saveable back stack of `Screen` objects, persisted as route strings so the
stack survives process death. Two things follow from that:

- **Screens carry role affinity.** `Screen.role` is `CONTROLLER`, `DISPLAY` or null. `rebaseForRole`
  rebuilds the whole stack on a role change: the new role's home becomes the root, and screens
  above it survive only if they are role-agnostic or belong to the new role.
- **A screen's parameters must fit in its route.** `Screen.Send(SendFocus)` encodes the chosen
  content kind as `send-text` / `send-qr` / `send-link`, so opening the composer on QR and then
  losing the process reopens it on QR.

`Screen.fromRoute` is a `when`, not a lookup table. An eagerly built companion `listOf(...)` was
the first thing touched when `rememberSaveable` restored the stack, and its class-initialisation
order could hand back nulls.

### Shared UI chrome

| Component | Used for |
| --- | --- |
| `RelayTopBar` | dashboard header: title at the start, gear at the end |
| `RelayDetailBar` | detail header: Back at the start |
| `ConnectionHero` | the connection state, as the one tonally filled block on a screen |
| `ActionTile` | one action in a grid; 88dp tall, outlined when disabled |
| `RelaySection` | a titled group; label and card are one unit so the rhythm cannot drift |
| `RelayGlyph` | all eleven icons, drawn on a `Canvas` |

Icons are Canvas paths rather than the material-icons artifact, which this Compose BOM does not
ship. A stroked path also stays crisp at any density, which matters on the Lenovo where a
rasterised asset gets resampled. One `STROKE_RATIO` keeps the set at a single optical weight.

Only `ConnectionHero` is tonally filled. Plain cards are `surface` with a 1dp outline, so the
connection state is the one tinted block on any screen instead of one lavender card among five.

### Role switching

`RoleSwitchCoordinator` turns a role change into one serialized transition. `Mutex.tryLock`
drops a repeated tap rather than queueing it. Teardown runs outside-in -- capture, transfers,
session, listener and discovery, OS resources, presentation, service -- and the new role is
**persisted last**, so an interrupted switch leaves the old role with everything stopped rather
than a new role owning old sockets. The whole sequence runs under `NonCancellable`.
