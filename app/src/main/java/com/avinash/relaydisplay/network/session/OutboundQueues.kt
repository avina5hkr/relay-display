package com.avinash.relaydisplay.network.session

import com.avinash.relaydisplay.protocol.Ack
import com.avinash.relaydisplay.protocol.Bye
import com.avinash.relaydisplay.protocol.ContentAccept
import com.avinash.relaydisplay.protocol.ContentOffer
import com.avinash.relaydisplay.protocol.ContentReject
import com.avinash.relaydisplay.protocol.ErrorMessage
import com.avinash.relaydisplay.protocol.FileBatchCancel
import com.avinash.relaydisplay.protocol.FileBatchOffer
import com.avinash.relaydisplay.protocol.FileBatchAccept
import com.avinash.relaydisplay.protocol.FileBatchReject
import com.avinash.relaydisplay.protocol.MirrorConfig
import com.avinash.relaydisplay.protocol.MirrorFrame
import com.avinash.relaydisplay.protocol.MirrorKeyframeRequest
import com.avinash.relaydisplay.protocol.MirrorStart
import com.avinash.relaydisplay.protocol.MirrorStop
import com.avinash.relaydisplay.protocol.PdfPageCommand
import com.avinash.relaydisplay.protocol.Ping
import com.avinash.relaydisplay.protocol.Pong
import com.avinash.relaydisplay.protocol.PresentCommand
import com.avinash.relaydisplay.protocol.PresentationDismiss
import com.avinash.relaydisplay.protocol.PresentationStateMessage
import com.avinash.relaydisplay.protocol.PresentationSyncRequest
import com.avinash.relaydisplay.protocol.RelayMessage
import com.avinash.relaydisplay.protocol.ShowFile
import com.avinash.relaydisplay.protocol.ShowLink
import com.avinash.relaydisplay.protocol.ShowQr
import com.avinash.relaydisplay.protocol.ShowText
import com.avinash.relaydisplay.protocol.TransferCancel
import com.avinash.relaydisplay.protocol.TransferChunk
import com.avinash.relaydisplay.protocol.TransferComplete
import com.avinash.relaydisplay.protocol.TransferStart

/**
 * Which queue a message travels on, and therefore what happens to it under pressure.
 *
 * Every outbound message used to share one 8-slot channel. Screen mirroring pushes ~24 frames a
 * second into it, so the queue is routinely full; the heartbeat then failed to enqueue a `Ping`
 * and closed the entire session with "outbound queue full". That is the mechanism behind mirroring
 * dying about a minute after it starts: pings land at 15, 30, 45 and 60 seconds, and each one is
 * a fresh chance to hit a queue that video has filled.
 *
 * Splitting by class fixes the cause rather than the symptom. Losing a video frame is normal and
 * invisible; losing a `Ping`, a `MirrorStop` or a decoder error is not.
 */
enum class TrafficClass {
    /**
     * Small, reliable, and never dropped or delayed behind anything else.
     *
     * Liveness (`Ping`/`Pong`), teardown (`Bye`, `MirrorStop`), acknowledgements, errors,
     * presentation state and commands, and mirror negotiation. If this queue ever fills, the
     * writer really is wedged and the session is genuinely dead -- unlike a full media queue,
     * which just means the link is slower than the encoder.
     */
    CONTROL,

    /**
     * Reliable bulk payloads: file and image transfer chunks.
     *
     * Backpressured rather than dropped -- a transfer that loses a chunk is a corrupt transfer.
     * The sender suspends when this fills, which is the intended signal to stop reading the file.
     */
    BULK,

    /**
     * Lossy real-time media: encoded video frames.
     *
     * Dropped oldest-first when full. Queueing video only adds latency to frames that are already
     * stale by the time they would be written, so a backlog is worse than a gap.
     */
    MEDIA,
}

/** Classifies an outbound message. Exhaustive by construction: a new type must choose a class. */
fun RelayMessage.trafficClass(): TrafficClass = when (this) {
    // Liveness and teardown. These are the messages whose loss the old design could not survive.
    is Ping, is Pong, is Bye, is Ack, is ErrorMessage -> TrafficClass.CONTROL

    // Mirror negotiation and teardown. Everything about a mirror except its frames is control:
    // dropping a MirrorStop leaves a phone capturing its own screen for nobody.
    is MirrorStart, is MirrorConfig, is MirrorStop, is MirrorKeyframeRequest -> TrafficClass.CONTROL

    // Presentation state is how the two phones agree on what is on screen.
    is PresentationStateMessage, is PresentationDismiss, is PresentationSyncRequest,
    is PresentCommand, is PdfPageCommand,
    -> TrafficClass.CONTROL

    // Content that is small and must arrive: the display cannot show text it never received.
    is ShowText, is ShowQr, is ShowLink, is ShowFile -> TrafficClass.CONTROL

    // Transfer negotiation is control; the bytes themselves are bulk. Batch confirmation belongs
    // with the negotiation: it is three small messages that gate everything after them, and a
    // dropped accept would strand the sender waiting on a decision the user already made.
    //
    // TransferStart is safe here even though the chunks it introduces are BULK: control overtaking
    // it can only make it arrive *earlier*, and it has to precede the chunks anyway.
    is ContentOffer, is ContentAccept, is ContentReject,
    is TransferStart, is TransferCancel,
    is FileBatchOffer, is FileBatchAccept, is FileBatchReject, is FileBatchCancel,
    -> TrafficClass.CONTROL

    // File bodies. Backpressured, so a slow display slows the sender rather than filling its heap,
    // and never able to starve the control queue.
    //
    // TransferComplete rides the SAME queue, and that is not a detail. The writer is strict
    // priority: anything on CONTROL is written before anything on BULK. A completion on CONTROL
    // therefore overtakes chunks still sitting in the bulk queue, and the receiver -- which
    // requires chunks in order and checks the byte count before the digest -- rejects the transfer
    // with a short count for a file that was never actually corrupt.
    //
    // Found on hardware, not by inspection: a 19 MB APK arrived as exactly 301 of 304 chunks
    // (19726336 of 19910151 bytes) because the last three were still queued when the completion
    // jumped ahead of them. Small transfers never show it, because BULK has no backlog to jump.
    // Being on one FIFO queue with the chunks is what makes "complete" mean "after the bytes".
    //
    // TransferCancel deliberately stays on CONTROL: cancelling is *meant* to overtake the backlog.
    is TransferChunk, is TransferComplete -> TrafficClass.BULK

    is MirrorFrame -> TrafficClass.MEDIA

    // Handshake messages never travel this path; they are written directly by HandshakeRunner
    // before the session exists. Treat anything unclassified as control, because the safe failure
    // for an unknown message is to deliver it reliably rather than to drop it.
    else -> TrafficClass.CONTROL
}

/** Counters for the queues, exposed for diagnostics and asserted by tests. */
data class OutboundMetrics(
    val controlDepth: Int = 0,
    val bulkDepth: Int = 0,
    val mediaDepth: Int = 0,
    val mediaDropped: Long = 0,
    /** Longest observed gap between queueing a control message and writing it, in ms. */
    val maxControlDelayMs: Long = 0,
)
