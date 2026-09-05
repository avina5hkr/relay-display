package com.avinash.relaydisplay.network.discovery

/**
 * One mDNS browse shared by consumers with different lifetimes.
 *
 * There are two: a connection attempt wants the first compatible endpoint and then stops, while
 * the pairing screen wants a live list for as long as it is on screen. [NsdBrowser] holds a single
 * discovery listener, so running two independent browses was never an option — the second would
 * silently cancel the first.
 *
 * Getting this wrong is expensive in both directions. Releasing too eagerly kills a live
 * connection attempt; failing to release leaves mDNS and its multicast lock running for the life
 * of the process, which on the older phone is measurable battery. So the counting lives here, on
 * its own, where it can be tested without a socket or a `Context`.
 */
class BrowseLease(
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
) {
    private val lock = Any()
    private var holders = 0

    /** Whether a browse is currently running. */
    val active: Boolean get() = synchronized(lock) { holders > 0 }

    /** Visible for tests: how many consumers are holding the browse open. */
    val holderCount: Int get() = synchronized(lock) { holders }

    /** Starts the browse if it is not already running, and claims a share of it. */
    fun retain() {
        synchronized(lock) {
            if (holders++ == 0) onStart()
        }
    }

    /**
     * Gives up one share, stopping the browse when the last one goes.
     *
     * Safe to call more often than [retain] was: a screen disposing twice, or a `finally` running
     * after a teardown already reset the lease, must not drive the count negative and leave the
     * next [retain] unable to start anything.
     */
    fun release() {
        synchronized(lock) {
            if (holders == 0) return
            if (--holders == 0) onStop()
        }
    }

    /**
     * Drops every share at once, for a full teardown.
     *
     * Without this, closing everything would stop the browser underneath the lease while the count
     * still claimed a browse was live, and the next [retain] would return without starting one.
     */
    fun reset() {
        synchronized(lock) {
            val wasActive = holders > 0
            holders = 0
            if (wasActive) onStop()
        }
    }
}
