# Reproduction and root-cause analysis

Captured from the two paired devices before any code change.

- S22 Ultra (Wi-Fi debugging), Android 16 / API 36
- Lenovo K33a42 (USB), Android 7.0 / API 24

Raw captures: `diagnostics/logs/pre_<serial>.log` -- gitignored, because they carry local
addresses and device names. Serials are left out of this file for the same reason: they are
stable hardware identifiers and this file *is* committed.

## Correlated timeline of a broken role switch

Both phones were switched to opposite roles. Wall-clock correlated:

| Time | S22 | Lenovo |
| --- | --- | --- |
| 20:53:29.956 | `hard reset: role change`, `engine stopping` | |
| 20:53:34.839 | `role change requested` → `hard reset` → `Paused -> Idle` | |
| 20:53:40.965 | `role change requested` → `hard reset` (3rd in 11 s) | |
| 20:53:52.036 | `listening on port 32983` — **now running the DISPLAY cycle** | |
| 20:53:53.072 | `advertising on port 32983` | |
| 20:53:54.268 | `Connected` | |
| 21:02:00.121 | `closing the session after a network change` | |
| 21:02:02.951 | `listening on port 43989` (rebind, new port) | |
| 21:02:03.806 | `advertising on port 43989` | |
| 21:02:04.824 | | `Discovering -> Connecting` |
| 21:02:04.958 | | `Connected` to `samsung SM-S908E` |
| 21:02:08.327 | | `Connected -> Reconnecting(#3)` |
| 21:02:18.608 | | `Discovering -> Reconnecting(#1)` ← counter goes **backwards** |
| 21:02:22.572 | `advertisement withdrawn`, `Connected -> Reconnecting(#2)` | |
| 21:02:22.605 | | `Discovering -> Connecting` (to the port S22 just withdrew) |
| 21:02:22.613 | | `Connecting -> Reconnecting(#2)` — connect failed |
| 21:02:24.960 | `listening on port 42899` (third port) | |
| 21:02:27.772 | | `Connected` — recovered after ~25 s and 3 failed attempts |

No `FATAL EXCEPTION`, `ANR`, `NsdManager` error, `SocketException` or
`ForegroundService*Exception` in either buffer. The service is correctly foregrounded on both
(`isForeground=true foregroundId=1001 types=0x10` = `connectedDevice`). **The failure is
logical, not a crash.**

## Root causes

### RC-1 — No presentation identity anywhere (causes Problem 1 and most of Problem 3)

`grep -rn "presentationId\|presentationRevision\|sessionId"` over the whole source tree returned
**nothing**. Content messages carry only a message UUID used for at-most-once application. There
is no notion of "which presentation is current", so:

- an acknowledgement from a previous content send cannot be distinguished from the current one;
- a message that arrives after a reconnect is applied to whatever session is now live;
- nothing can express "revision 4 supersedes revision 3".

### RC-2 — The Controller never models what the Display is actually showing

`SendViewModel` sets a local string on send:

```kotlin
messages.value = Messages(notice = "Sent as text.")
```

That is fire-and-forget optimism. It is never reconciled against the Display, so the Controller
keeps claiming content is shown after the Display has moved on. This is exactly the reported
"one phone says one thing while the other displays something else".

### RC-3 — The Display has no way to close content

`PresentationSurface` exposes no Close affordance. The only exit is `BackHandler`, which first
consumes a press to leave immersive mode, so the phone appears stuck. There is no
`PRESENTATION_DISMISSED` message — `grep -rn DISMISS` finds only unrelated Compose dialog
callbacks. The Display genuinely cannot get out without the Controller.

### RC-4 — Role change is not serialized

Three `hard reset: role change` entries in 11 seconds (20:53:29, :34, :40) with no guard. The
current implementation is `sessionCoordinator.hardReset()` followed by a DataStore write, with
no `SwitchingRole` state, no mutex, no double-tap protection, and no bounded wait for shutdown
before the new role's runtime starts.

### RC-5 — Reconnect attempt counter is non-monotonic

Lenovo reported `Reconnecting(#3)` → `#1` → `#2`. `ReconnectBackoff` is constructed inside
`RelayEngine.runLoop()`, so every restart of that loop resets the counter while the user is
still watching one continuous outage. Cosmetic, but it is misleading status text.

### RC-6 — Endpoint churn during recovery

The S22 bound three different ports (32983 → 43989 → 42899) in nine minutes because the listener
is recreated per display cycle with port 0. The Lenovo dialled a port that had just been
withdrawn (21:02:22.605), wasting a full backoff step. Not a correctness bug, but it is the
reason recovery took ~25 s instead of ~2 s.

## Fix order

Per the task instruction, authoritative state ownership and stale-event rejection are fixed
first (RC-1, RC-2), then the Display's Close/Copy affordance (RC-3), then role switching (RC-4),
then the status-accuracy issues (RC-5, RC-6), then UX and legal polish.
