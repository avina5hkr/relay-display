package com.avinash.relaydisplay.network

import com.avinash.relaydisplay.network.session.ReconnectBackoff
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    private fun noJitter() = ReconnectBackoff(jitterFraction = 0.0)

    @Test
    fun `climbs the documented ladder`() {
        val b = noJitter()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L), (1..6).map { b.nextDelayMs() })
    }

    @Test
    fun `saturates at the top of the ladder`() {
        val b = noJitter()
        repeat(6) { b.nextDelayMs() }
        repeat(20) { assertEquals(30_000L, b.nextDelayMs()) }
    }

    @Test
    fun `reset returns to the bottom`() {
        val b = noJitter()
        repeat(4) { b.nextDelayMs() }
        assertEquals(4, b.attemptCount())
        b.reset()
        assertEquals(0, b.attemptCount())
        assertEquals(1_000L, b.nextDelayMs())
    }

    @Test
    fun `peek does not advance`() {
        val b = noJitter()
        assertEquals(1_000L, b.peekDelayMs())
        assertEquals(1_000L, b.peekDelayMs())
        assertEquals(0, b.attemptCount())
    }

    @Test
    fun `jitter stays inside the configured fraction`() {
        val b = ReconnectBackoff(jitterFraction = 0.2, random = Random(1234))
        repeat(200) {
            val expectedBase = b.peekDelayMs()
            val delay = b.nextDelayMs()
            val span = (expectedBase * 0.2).toLong()
            assertTrue("$delay outside $expectedBase +/- $span", delay in (expectedBase - span)..(expectedBase + span))
        }
    }

    @Test
    fun `jitter never produces a busy loop`() {
        val b = ReconnectBackoff(ladderMs = longArrayOf(300), jitterFraction = 0.9, random = Random(7))
        repeat(500) { assertTrue(b.nextDelayMs() >= 250L) }
    }

    @Test
    fun `two devices that dropped together do not retry in lockstep`() {
        val a = ReconnectBackoff(random = Random(1))
        val b = ReconnectBackoff(random = Random(2))
        val differences = (1..6).count { a.nextDelayMs() != b.nextDelayMs() }
        assertTrue("jitter should separate independent devices", differences >= 4)
    }
}
