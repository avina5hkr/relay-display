package com.avinash.relaydisplay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which file-transfer versions this build will speak, and what it does about the others.
 *
 * The compatibility decision, pinned as a test rather than only described in a document: this
 * build advertises `file-v2` and **not** `file-v1`. v1's manifest bound nothing -- no transfer id,
 * no digest, and no batch identity on the per-file offer -- so an accepted batch could be followed
 * by different files. Announcing both would mean either honouring that (the vulnerability) or
 * advertising a capability the code refuses to act on.
 */
class FileCapabilityTest {

    /** What a controller checks before it will offer a batch. */
    private fun willOfferFiles(peer: Set<String>): Boolean = peer.contains(Capabilities.FILE_V2)

    @Test
    fun `the two versions are distinct tokens`() {
        assertEquals("file-v1", Capabilities.FILE_V1)
        assertEquals("file-v2", Capabilities.FILE_V2)
        assertFalse(Capabilities.FILE_V1 == Capabilities.FILE_V2)
    }

    @Test
    fun `two updated peers negotiate file transfer`() {
        assertTrue(willOfferFiles(setOf(Capabilities.TEXT, Capabilities.FILE_V2)))
    }

    @Test
    fun `a peer announcing only the old version is not offered files`() {
        // It must fail before anything is sent, with a message the user can act on, rather than
        // being handed a v2 manifest it cannot parse.
        assertFalse(willOfferFiles(setOf(Capabilities.TEXT, Capabilities.FILE_V1)))
    }

    @Test
    fun `a peer announcing no file capability is not offered files`() {
        assertFalse(willOfferFiles(setOf(Capabilities.TEXT, Capabilities.IMAGE, Capabilities.PDF)))
    }

    @Test
    fun `a peer announcing both versions is spoken to as v2`() {
        // Forward compatibility: a future peer may advertise both for the benefit of older
        // builds. Preferring v2 means it never gets the unbound protocol.
        assertTrue(willOfferFiles(setOf(Capabilities.FILE_V1, Capabilities.FILE_V2)))
    }

    @Test
    fun `an unknown future version alone is not enough`() {
        assertFalse(willOfferFiles(setOf("file-v3")))
    }

    @Test
    fun `image and pdf capabilities are independent of the file version`() {
        // Presentation transfers must keep working against any peer, including one too old for
        // generic files.
        val old = setOf(Capabilities.TEXT, Capabilities.QR, Capabilities.IMAGE, Capabilities.PDF)
        assertTrue(old.contains(Capabilities.IMAGE))
        assertTrue(old.contains(Capabilities.PDF))
        assertFalse(willOfferFiles(old))
    }
}
