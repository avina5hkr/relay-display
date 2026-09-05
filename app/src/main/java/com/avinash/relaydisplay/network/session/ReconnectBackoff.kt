package com.avinash.relaydisplay.network.session

import kotlin.random.Random

/**
 * Bounded exponential backoff with jitter.
 *
 * The ladder is fixed rather than computed so the worst case is obvious: about 1, 2, 4, 8, 15
 * then 30 seconds, and 30 seconds forever after. Jitter spreads two phones that lost the same
 * access point so they do not retry in lockstep.
 */
class ReconnectBackoff(
    private val ladderMs: LongArray = DEFAULT_LADDER,
    private val jitterFraction: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    /** Attempts made since the last [reset], starting at 0. */
    fun attemptCount(): Int = attempt

    /** Advances one step and returns how long to wait. */
    fun nextDelayMs(): Long {
        val base = ladderMs[minOf(attempt, ladderMs.size - 1)]
        attempt++
        return applyJitter(base)
    }

    /** What the next delay would be, without advancing. Used for the countdown in the UI. */
    fun peekDelayMs(): Long = ladderMs[minOf(attempt, ladderMs.size - 1)]

    /** Call after a connection has been stable; the next drop starts from the bottom again. */
    fun reset() {
        attempt = 0
    }

    private fun applyJitter(base: Long): Long {
        if (jitterFraction <= 0.0) return base
        val span = (base * jitterFraction).toLong()
        if (span <= 0) return base
        // Symmetric jitter around the base, never below a quarter of a second.
        val offset = random.nextLong(-span, span + 1)
        return maxOf(250L, base + offset)
    }

    companion object {
        val DEFAULT_LADDER = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

        /** A connection that lasted this long counts as stable and resets the ladder. */
        const val STABLE_CONNECTION_MS = 20_000L
    }
}
