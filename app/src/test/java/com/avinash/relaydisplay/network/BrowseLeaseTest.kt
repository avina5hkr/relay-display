package com.avinash.relaydisplay.network

import com.avinash.relaydisplay.network.discovery.BrowseLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The counting behind a single shared mDNS browse.
 *
 * These exist because the bug they guard against was real: discovery ran only inside a connection
 * attempt, so the pairing screen's list of displays could never fill. The fix gave one browse two
 * consumers with different lifetimes, and every case below is a way that arrangement can go wrong.
 */
class BrowseLeaseTest {

    private class Recorder {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        val lease = BrowseLease(
            onStart = { starts.incrementAndGet() },
            onStop = { stops.incrementAndGet() },
        )
    }

    @Test
    fun `first retain starts the browse`() {
        val r = Recorder()
        r.lease.retain()
        assertEquals(1, r.starts.get())
        assertTrue(r.lease.active)
    }

    @Test
    fun `a second consumer does not restart the browse`() {
        val r = Recorder()
        r.lease.retain()
        r.lease.retain()
        assertEquals("restarting would cancel the first consumer's listener", 1, r.starts.get())
        assertEquals(0, r.stops.get())
    }

    @Test
    fun `the browse survives until the last consumer leaves`() {
        val r = Recorder()
        r.lease.retain()
        r.lease.retain()
        r.lease.release()
        assertEquals("a connect attempt must not be killed by a screen closing", 0, r.stops.get())
        assertTrue(r.lease.active)
        r.lease.release()
        assertEquals(1, r.stops.get())
        assertFalse(r.lease.active)
    }

    @Test
    fun `release without retain does nothing`() {
        val r = Recorder()
        r.lease.release()
        assertEquals(0, r.stops.get())
        assertEquals(0, r.lease.holderCount)
    }

    @Test
    fun `extra releases cannot drive the count negative`() {
        val r = Recorder()
        r.lease.retain()
        r.lease.release()
        r.lease.release()
        r.lease.release()
        assertEquals(0, r.lease.holderCount)

        // The real failure this guards: a negative count means a later retain increments to zero
        // or below and never starts a browse, so discovery silently stops working for good.
        r.lease.retain()
        assertEquals(2, r.starts.get())
        assertTrue(r.lease.active)
    }

    @Test
    fun `retain after the last release starts a fresh browse`() {
        val r = Recorder()
        r.lease.retain()
        r.lease.release()
        r.lease.retain()
        assertEquals(2, r.starts.get())
        assertEquals(1, r.stops.get())
    }

    @Test
    fun `reset drops every consumer and stops once`() {
        val r = Recorder()
        r.lease.retain()
        r.lease.retain()
        r.lease.retain()
        r.lease.reset()
        assertEquals(1, r.stops.get())
        assertFalse(r.lease.active)
        assertEquals(0, r.lease.holderCount)
    }

    @Test
    fun `reset on an idle lease does not stop anything`() {
        val r = Recorder()
        r.lease.reset()
        assertEquals("stopping a browser that never started is a spurious platform call", 0, r.stops.get())
    }

    @Test
    fun `a consumer releasing after a reset does not stop the next browse`() {
        val r = Recorder()
        r.lease.retain()
        r.lease.reset()
        // The screen's DisposableEffect runs after the engine has already torn everything down.
        r.lease.release()
        r.lease.retain()
        assertTrue("the browse started after teardown must stay running", r.lease.active)
        assertEquals(1, r.stops.get())
    }

    @Test
    fun `concurrent retains start the browse exactly once`() {
        val r = Recorder()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        repeat(threads) {
            pool.submit {
                ready.countDown()
                go.await()
                r.lease.retain()
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(1, r.starts.get())
        assertEquals(threads, r.lease.holderCount)
    }

    @Test
    fun `concurrent releases stop the browse exactly once`() {
        val r = Recorder()
        val threads = 16
        repeat(threads) { r.lease.retain() }
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        repeat(threads) {
            pool.submit {
                go.await()
                r.lease.release()
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals(1, r.stops.get())
        assertFalse(r.lease.active)
    }
}
