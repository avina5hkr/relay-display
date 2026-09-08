package com.avinash.relaydisplay.content

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.avinash.relaydisplay.data.settings.SettingsRepository
import com.avinash.relaydisplay.diagnostics.DiagnosticsLog
import com.avinash.relaydisplay.domain.model.ContentKind
import com.avinash.relaydisplay.domain.model.DeviceRole
import com.avinash.relaydisplay.network.session.HandshakeRunner
import com.avinash.relaydisplay.network.session.RelayError
import com.avinash.relaydisplay.network.session.RelaySession
import com.avinash.relaydisplay.network.session.SessionHost
import com.avinash.relaydisplay.network.session.SessionListener
import com.avinash.relaydisplay.network.transport.LoopbackPair
import com.avinash.relaydisplay.network.transport.SecureConnection
import com.avinash.relaydisplay.protocol.Capabilities
import com.avinash.relaydisplay.protocol.FileBatchAccept
import com.avinash.relaydisplay.protocol.FileBatchCancel
import com.avinash.relaydisplay.protocol.FileBatchOffer
import com.avinash.relaydisplay.protocol.RelayMessage
import com.avinash.relaydisplay.protocol.TransferCancel
import com.avinash.relaydisplay.security.HandshakeConfig
import com.avinash.relaydisplay.security.HandshakeOutcome
import com.avinash.relaydisplay.security.SoftwareDeviceIdentity
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The batch lifecycle, driven through two real routers over a real encrypted session.
 *
 * The loopback transport means everything but the socket is genuine: handshake, record encryption,
 * the priority writer, both `ContentRouter`s, and both caches on disk. That matters here because
 * the bug being pinned is an *ordering* bug, and ordering only exists on a real wire.
 *
 * What it pins: cancelling a batch used to be a purely local act on the controller --
 * `batchJob.cancel()` and a StateFlow update. `streamOneFile` rethrows `CancellationException`
 * before it can send anything, so the Display was never told. It kept its consent, its open
 * partial and its "transfer running" state, and refused every later batch as `BUSY` until the
 * session ended. The fix sends `FILE_BATCH_CANCEL` on the still-live session *first*, then cancels
 * the coroutine, and these tests assert the peer actually receives it.
 */
class BatchLifecycleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val controllerIdentity = SoftwareDeviceIdentity.generate()
    private val displayIdentity = SoftwareDeviceIdentity.generate()

    private lateinit var scope: CoroutineScope
    private lateinit var controllerConn: SecureConnection
    private lateinit var displayConn: SecureConnection

    private lateinit var controllerHost: TestHost
    private lateinit var displayHost: TestHost
    private lateinit var controllerRouter: ContentRouter
    private lateinit var displayRouter: ContentRouter
    private lateinit var controllerCache: ContentCache
    private lateinit var displayCache: ContentCache

    /** Every message the display's session handed up, so ordering can be asserted. */
    private val displayInbox = LinkedBlockingQueue<RelayMessage>()
    private val controllerInbox = LinkedBlockingQueue<RelayMessage>()

    /**
     * A [SessionHost] over one real session.
     *
     * `RelaySession` is concrete and owns a `SecureConnection`, so there is no seam to fake; the
     * real one over a loopback pair is both the easiest and the most faithful option.
     */
    private class TestHost : SessionHost {
        val session = MutableStateFlow<RelaySession?>(null)
        val inbound = MutableSharedFlow<RelayMessage>(replay = 0, extraBufferCapacity = 128)
        override val activeSession: StateFlow<RelaySession?> get() = session
        override val messages: SharedFlow<RelayMessage> get() = inbound
    }

    /** Bridges the session's listener callbacks into the host's message flow and a test inbox. */
    private inner class Bridge(
        private val host: TestHost,
        private val inbox: LinkedBlockingQueue<RelayMessage>,
    ) : SessionListener {
        override fun onMessage(message: RelayMessage) {
            inbox.add(message)
            host.inbound.tryEmit(message)
        }

        override fun onClosed(error: RelayError?) = Unit
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val (cc, dc, outcomes) = handshakePair()
        controllerConn = cc
        displayConn = dc

        controllerHost = TestHost()
        displayHost = TestHost()

        val controllerSession = RelaySession(cc, outcomes.first, Bridge(controllerHost, controllerInbox), scope)
        val displaySession = RelaySession(dc, outcomes.second, Bridge(displayHost, displayInbox), scope)
        controllerSession.start()
        displaySession.start()

        controllerCache = ContentCache(temp.newFolder("controller-cache"))
        displayCache = ContentCache(temp.newFolder("display-cache"))

        controllerRouter = router(controllerHost, DeviceRole.CONTROLLER, controllerCache, "ctl")
        displayRouter = router(displayHost, DeviceRole.DISPLAY, displayCache, "dsp")

        // Published last, so each router's session collector sees a started session.
        controllerHost.session.value = controllerSession
        displayHost.session.value = displaySession
        settle()
    }

    @After
    fun tearDown() {
        runCatching { controllerConn.close() }
        runCatching { displayConn.close() }
        scope.cancel()
    }

    private fun router(
        host: TestHost,
        role: DeviceRole,
        cache: ContentCache,
        prefix: String,
    ): ContentRouter {
        val file = File(temp.newFolder("$prefix-settings"), "settings.preferences_pb")
        val store: DataStore<Preferences> =
            PreferenceDataStoreFactory.createWithPath(scope = scope) { file.toOkioPath() }
        val settings = SettingsRepository(store)
        runBlocking {
            settings.setRole(role)
            settings.setLocalDeviceName(if (role == DeviceRole.CONTROLLER) "S22" else "K6")
        }
        return ContentRouter(
            engine = host,
            presentation = PresentationController(),
            settingsRepository = settings,
            cacheProvider = { cache },
            diagnostics = DiagnosticsLog(),
            scope = scope,
            // Short enough that the expiry path can be exercised without waiting out the real two
            // and a half minutes, long enough that no other test trips it.
            consentExpiryMs = CONSENT_EXPIRY_TEST_MS,
        )
    }

    private fun handshakePair(): Triple<SecureConnection, SecureConnection, Pair<HandshakeOutcome, HandshakeOutcome>> {
        val pair = LoopbackPair()
        val cc = SecureConnection(pair.clientSide)
        val dc = SecureConnection(pair.serverSide)
        val displayOutcome = AtomicReference<HandshakeOutcome>()
        val failure = AtomicReference<Throwable>()
        val responder = thread {
            try {
                displayOutcome.set(
                    HandshakeRunner.runResponder(
                        dc,
                        HandshakeConfig(
                            role = DeviceRole.DISPLAY,
                            deviceId = "display-1",
                            deviceName = "K6 Power",
                            identity = displayIdentity,
                            // file-v2 is what a controller checks for before it will offer.
                            capabilities = setOf(Capabilities.TEXT, Capabilities.FILE_V2),
                        ),
                    ),
                )
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        val controllerOutcome = try {
            HandshakeRunner.runInitiator(
                cc,
                HandshakeConfig(
                    role = DeviceRole.CONTROLLER,
                    deviceId = "controller-1",
                    deviceName = "S22 Ultra",
                    identity = controllerIdentity,
                    capabilities = setOf(Capabilities.TEXT),
                ),
            )
        } finally {
            responder.join(10_000)
        }
        failure.get()?.let { throw it }
        return Triple(cc, dc, controllerOutcome to displayOutcome.get())
    }

    // -- helpers -------------------------------------------------------------------------------

    /** A source the controller can send: real bytes, on disk, so spooling has something to copy. */
    private fun source(name: String, bytes: Int): ContentSource {
        val f = File(temp.newFolder("src-${UUID.randomUUID()}"), name)
        f.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        return SpooledContentSource(f, mimeType = "application/octet-stream", displayName = name)
    }

    /** Lets the real threads make progress. Everything here is genuinely concurrent. */
    private fun settle(ms: Long = 400) = Thread.sleep(ms)

    /** Polls a condition rather than sleeping a guessed interval. */
    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** Waits for a message of the given type on the display, or returns null. */
    private inline fun <reified T : RelayMessage> awaitOnDisplay(timeoutMs: Long = 5_000): T? =
        awaitIn(displayInbox, timeoutMs)

    private inline fun <reified T : RelayMessage> awaitOnController(timeoutMs: Long = 5_000): T? =
        awaitIn(controllerInbox, timeoutMs)

    private inline fun <reified T : RelayMessage> awaitIn(
        inbox: LinkedBlockingQueue<RelayMessage>,
        timeoutMs: Long,
    ): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val next = inbox.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (next is T) return next
        }
        return null
    }

    private fun displayPartials(): List<File> =
        File(File(temp.root, "display-cache"), ContentCache.INCOMING_DIR)
            .listFiles()?.toList().orEmpty()

    private fun controllerSpool(): List<File> =
        File(File(temp.root, "controller-cache"), "spool").listFiles()?.toList().orEmpty()

    /** Offers one file and waits for the display to raise its consent prompt. */
    private fun offerOneFile(name: String = "doc.bin", bytes: Int = 64 * 1024): FileBatchOffer {
        val refusal = controllerRouter.sendFileBatch(listOf(source(name, bytes)))
        assertNull("the batch should have started, got $refusal", refusal)
        val offer = awaitOnDisplay<FileBatchOffer>()
        assertNotNull("the display never received the batch offer", offer)
        settle()
        assertNotNull("the display should be prompting", displayRouter.incomingBatch.value)
        return offer!!
    }

    // -- 1. cancellation notifies the peer before the coroutine dies --------------------------

    @Test
    fun `cancelling while the consent prompt is open reaches the display`() {
        offerOneFile()

        controllerRouter.cancelFileBatch()

        // The assertion that would have failed before the fix: the message has to arrive at all.
        // `streamOneFile` rethrows CancellationException before it can send anything, so unless
        // the notification goes out ahead of `batchJob.cancel()`, nothing is ever transmitted.
        val cancel = awaitOnDisplay<FileBatchCancel>()
        assertNotNull("the display was never told the batch was cancelled", cancel)
        settle()

        assertNull("the consent prompt must be gone", displayRouter.incomingBatch.value)
        assertTrue("no partial may survive", displayPartials().isEmpty())
        assertTrue("no spool copy may survive", controllerSpool().isEmpty())
    }

    @Test
    fun `cancelling during an active transfer reaches the display and aborts the transfer`() {
        // Big enough that streaming is still in flight when the cancel is issued. Over a loopback
        // socket a few megabytes finish in well under a second, so a small file here would race
        // the cancel and prove nothing.
        val refusal = controllerRouter.sendFileBatch(listOf(source("big.bin", 40 * 1024 * 1024)))
        assertNull(refusal)
        assertNotNull(awaitOnDisplay<FileBatchOffer>(15_000))
        settle()
        displayRouter.acceptIncomingBatch()

        // Poll until bytes are demonstrably in flight, then cancel at once. A fixed sleep cannot
        // do this reliably: over an in-memory loopback pair even tens of megabytes can finish in
        // a few hundred milliseconds, and if the transfer wins the race the test proves nothing.
        val started = waitUntil(10_000) {
            controllerRouter.fileBatch.value?.files?.any {
                it.phase is FilePhase.Sending && it.bytesTransferred > 0
            } == true
        }
        assertTrue("the transfer never started, so cancellation was not exercised", started)
        assertFalse(
            "the transfer finished before it could be cancelled; use a larger payload",
            controllerRouter.fileBatch.value!!.settled,
        )

        controllerRouter.cancelFileBatch()

        // The batch cancel is the contract: it has to arrive even though `streamOneFile` is
        // suspended in the middle of a chunk send and rethrows CancellationException.
        val batchCancel = awaitOnDisplay<FileBatchCancel>(10_000)
        assertNotNull("the display was never told the batch was cancelled", batchCancel)
        settle(1_000)

        assertTrue("the partial must be deleted", displayPartials().isEmpty())
        assertTrue("the spool copy must be deleted", controllerSpool().isEmpty())
        assertNull("consent must be spent", displayRouter.incomingBatch.value)
        // A cancelled transfer must not leave a promoted file behind.
        assertTrue(
            "an aborted transfer must not be promoted",
            displayCache.receivedFiles().none { it.displayName == "big.bin" },
        )
    }

    // -- 2. duplicate cancellation -------------------------------------------------------------

    @Test
    fun `cancelling twice is safe and leaves one clean terminal state`() {
        offerOneFile()

        controllerRouter.cancelFileBatch()
        controllerRouter.cancelFileBatch()
        controllerRouter.cancelFileBatch()
        settle(600)

        assertNull(displayRouter.incomingBatch.value)
        assertTrue(displayPartials().isEmpty())
        assertTrue(controllerSpool().isEmpty())
        // The batch state is terminal, and every file in it is terminal.
        val state = controllerRouter.fileBatch.value
        assertNotNull(state)
        assertTrue("the batch must be settled", state!!.settled)
    }

    @Test
    fun `a cancel naming an unknown batch is ignored rather than breaking the receiver`() {
        offerOneFile()

        // What a crossing or replayed cancel looks like. It must not clear the live batch.
        displayHost.inbound.tryEmit(
            FileBatchCancel(
                UUID.randomUUID(),
                UUID.randomUUID(),
                com.avinash.relaydisplay.protocol.ProtocolErrorCode.CANCELLED,
            ),
        )
        settle()
        assertNotNull("an unrelated cancel must not dismiss the prompt", displayRouter.incomingBatch.value)

        // The real one still works.
        controllerRouter.cancelFileBatch()
        assertNotNull(awaitOnDisplay<FileBatchCancel>())
        settle()
        assertNull(displayRouter.incomingBatch.value)
    }

    // -- 3. late acceptance --------------------------------------------------------------------

    @Test
    fun `an acceptance arriving after the controller gave up is ignored`() {
        val offer = offerOneFile()

        // The controller abandons the batch, as its decision timeout would.
        controllerRouter.cancelFileBatch()
        assertNotNull(awaitOnDisplay<FileBatchCancel>())
        settle(600)

        // Now a late accept for that dead batch arrives.
        controllerHost.inbound.tryEmit(FileBatchAccept(UUID.randomUUID(), offer.batchId))
        settle()

        // Nothing restarts: no new offer goes out, and the batch stays terminal.
        assertTrue(controllerRouter.fileBatch.value!!.settled)
        assertTrue(controllerSpool().isEmpty())
    }

    // -- 4. consent expiry, without waiting out the real timer --------------------------------

    @Test
    fun `an unanswered prompt expires locally and clears everything`() {
        offerOneFile()

        // The injected expiry, not the production 150 s. The point of the timer is that a lost
        // cancellation, or a controller that was force-stopped, cannot leave a prompt on this
        // phone that can never be answered and blocks every later batch as BUSY.
        Thread.sleep(CONSENT_EXPIRY_TEST_MS + 600)

        assertNull("the prompt must have expired", displayRouter.incomingBatch.value)
        assertTrue("no partial may survive an expiry", displayPartials().isEmpty())

        // And the controller is told, so its own side does not hang.
        assertNotNull("the controller should have been told", awaitOnController<FileBatchCancel>(3_000))
    }

    @Test
    fun `a new batch is accepted immediately after an expiry`() {
        offerOneFile("first.bin")
        Thread.sleep(CONSENT_EXPIRY_TEST_MS + 600)
        assertNull(displayRouter.incomingBatch.value)

        controllerRouter.clearFileBatch()
        val refusal = controllerRouter.sendFileBatch(listOf(source("second.bin", 4096)))
        assertNull("a new batch must be sendable after an expiry, got $refusal", refusal)
        val second = awaitOnDisplay<FileBatchOffer>()
        assertNotNull("the second offer was never received", second)
        settle()
        assertNotNull("the display must prompt again", displayRouter.incomingBatch.value)
    }

    // -- 5. another batch immediately after a cancellation ------------------------------------

    @Test
    fun `a new batch is accepted immediately after a cancellation, with no reconnect`() {
        offerOneFile("first.bin")
        controllerRouter.cancelFileBatch()
        assertNotNull(awaitOnDisplay<FileBatchCancel>())
        settle(600)

        controllerRouter.clearFileBatch()
        val refusal = controllerRouter.sendFileBatch(listOf(source("second.bin", 8192)))
        // The regression: the display used to still hold consent and refuse this with BUSY.
        assertNull("a new batch must be sendable after a cancellation, got $refusal", refusal)

        val second = awaitOnDisplay<FileBatchOffer>()
        assertNotNull("the second offer was never received", second)
        settle()
        assertNotNull("the display must prompt for the second batch", displayRouter.incomingBatch.value)

        // And it can be carried through to completion.
        displayRouter.acceptIncomingBatch()
        settle(1_500)
        assertTrue(
            "the second batch should have delivered a file",
            displayCache.receivedFiles().isNotEmpty(),
        )
    }

    @Test
    fun `a cancelled batch cleanup does not delete the next batch's spool file`() {
        // The race this pins was found on hardware. `batchJob.cancel()` flips `isActive` false at
        // once but the coroutine's `finally` runs later, so a cancelled batch's cleanup could fire
        // after the user had already started the next one. Cleanup used to delete whatever
        // `outbound` pointed at, which by then was the *new* batch -- and the next transfer failed
        // with FileNotFoundException at stream time, intermittently, depending on how long the
        // user took to accept.
        offerOneFile("first.bin")
        controllerRouter.cancelFileBatch()

        // Immediately, without waiting for the cancelled coroutine to unwind.
        controllerRouter.clearFileBatch()
        val refusal = controllerRouter.sendFileBatch(listOf(source("second.bin", 256 * 1024)))
        assertNull("the second batch should have started, got $refusal", refusal)
        assertNotNull("the second offer was never received", awaitOnDisplay<FileBatchOffer>(10_000))

        // Give the first batch's cleanup every chance to run and stamp on this one.
        settle(1_200)

        displayRouter.acceptIncomingBatch()
        settle(2_500)

        // The file must actually arrive: if the spool copy had been deleted, streaming would fail
        // with "could not read the file" and nothing would be received.
        val received = displayCache.receivedFiles()
        assertTrue(
            "the second batch delivered nothing; its spool file was probably deleted by the " +
                "first batch's cleanup",
            received.any { it.displayName == "second.bin" },
        )
        assertEquals(256L * 1024, received.first { it.displayName == "second.bin" }.sizeBytes)
    }

    @Test
    fun `repeated cancel and resend cycles leave no residue`() {
        repeat(3) { round ->
            controllerRouter.clearFileBatch()
            val refusal = controllerRouter.sendFileBatch(listOf(source("round-$round.bin", 4096)))
            assertNull("round $round refused: $refusal", refusal)
            assertNotNull("round $round offer lost", awaitOnDisplay<FileBatchOffer>())
            settle()
            controllerRouter.cancelFileBatch()
            assertNotNull("round $round cancel lost", awaitOnDisplay<FileBatchCancel>())
            settle(500)

            assertNull("round $round left a prompt", displayRouter.incomingBatch.value)
            assertTrue("round $round left a partial", displayPartials().isEmpty())
            assertTrue("round $round left a spool file", controllerSpool().isEmpty())
        }
    }

    // -- 6. the happy path, so the cancellation tests are not proving a broken feature --------

    @Test
    fun `an accepted batch delivers the file and both sides reach a terminal state`() {
        offerOneFile("payload.bin", 128 * 1024)
        displayRouter.acceptIncomingBatch()
        settle(2_000)

        val received = displayCache.receivedFiles()
        assertEquals(1, received.size)
        assertEquals("payload.bin", received.single().displayName)
        assertEquals(128L * 1024, received.single().sizeBytes)

        assertNull("consent must be spent once the batch completes", displayRouter.incomingBatch.value)
        assertTrue("the spool copy must be released", controllerSpool().isEmpty())
        assertTrue(displayPartials().isEmpty())
        assertTrue(controllerRouter.fileBatch.value!!.settled)
        assertEquals(1, controllerRouter.fileBatch.value!!.completedCount)
    }

    @Test
    fun `a generic offer with no accepted batch is refused and writes nothing`() {
        // The gate, over the real wire: an offer that no consent covers must not create a partial.
        controllerHost.session.value!!.trySend(
            com.avinash.relaydisplay.protocol.ContentOffer(
                id = UUID.randomUUID(),
                transferId = UUID.randomUUID(),
                kind = ContentKind.FILE,
                sizeBytes = 16,
                mimeType = "application/octet-stream",
                displayName = "sneaky.bin",
                sha256 = ByteArray(32),
                batchId = UUID.randomUUID(),
                manifestIndex = 0,
            ),
        )
        settle(800)

        assertTrue("nothing may be written without consent", displayCache.receivedFiles().isEmpty())
        assertTrue("no partial may be created without consent", displayPartials().isEmpty())
    }

    private companion object {
        /** Long enough not to fire during the other tests, short enough not to slow the suite. */
        const val CONSENT_EXPIRY_TEST_MS = 1_200L
    }
}
