package com.avinash.relaydisplay.network

import com.avinash.relaydisplay.network.transport.TcpListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The contract `RelayEngine.stop()` depends on: closing a listener unblocks a thread parked in
 * `accept()`.
 *
 * This is not a theoretical property. `stop()` cancels the run loop and then joins it, and the
 * display cycle spends nearly all of its life inside a blocking `accept()`. Coroutine
 * cancellation cannot interrupt that call — only closing the socket can — so for a while `stop()`
 * simply waited. On a role change it surfaced as "shutdown timed out; continuing", the old role's
 * listener still bound afterwards, and the engine never restarting in the new role.
 */
class ListenerShutdownTest {

    @Test
    fun `closing a listener unblocks a thread parked in accept`() {
        val listener = TcpListener()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)

        val blocked = Thread {
            entered.countDown()
            try {
                listener.accept()
            } catch (e: IOException) {
                thrown.set(e)
            } finally {
                returned.countDown()
            }
        }
        blocked.start()

        assertTrue("the thread never reached accept()", entered.await(5, TimeUnit.SECONDS))
        // Give it a moment to actually be inside the syscall rather than just past the latch.
        Thread.sleep(200)
        assertFalse("accept() should still be blocking with nobody connecting", returned.await(0, TimeUnit.MILLISECONDS))

        listener.close()

        assertTrue(
            "accept() must return once the socket is closed, or stop() can never join",
            returned.await(5, TimeUnit.SECONDS),
        )
        blocked.join(5_000)
        assertTrue("closing should surface as an IOException, not a hang", thrown.get() is IOException)
    }

    @Test
    fun `closing twice is safe`() {
        val listener = TcpListener()
        listener.close()
        listener.close()
        assertTrue(listener.isClosed)
    }

    @Test
    fun `a closed listener reports itself closed`() {
        val listener = TcpListener()
        assertFalse(listener.isClosed)
        assertTrue("the OS must have chosen a real port", listener.port > 0)
        listener.close()
        assertTrue(listener.isClosed)
    }
}
