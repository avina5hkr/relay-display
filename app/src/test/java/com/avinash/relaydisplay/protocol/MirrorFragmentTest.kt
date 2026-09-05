package com.avinash.relaydisplay.protocol

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame sizes around the protocol's field cap.
 *
 * The failure these pin was reproduced on hardware: pressing Home and opening another app forces a
 * full-screen keyframe, that keyframe exceeded the 192 KiB field cap, the receiver rejected the
 * record with PAYLOAD_TOO_LARGE, and the whole session was torn down. The rule is that **no frame,
 * at any size, may terminate the session** -- it may only be fragmented, or dropped.
 */
class MirrorFragmentTest {

    private fun frame(size: Int, index: Int = 0, count: Int = 1, seq: Long = 1) = MirrorFrame(
        id = UUID.randomUUID(),
        presentationTimeUs = 1_234L,
        keyFrame = true,
        data = ByteArray(size) { (it % 251).toByte() },
        frameSequence = seq,
        fragmentIndex = index,
        fragmentCount = count,
    )

    private fun roundTrip(m: MirrorFrame): MirrorFrame {
        val encoded = MessageCodec.encode(m)
        return MessageCodec.decode(encoded) as MirrorFrame
    }

    @Test
    fun `a fragment at the cap round-trips`() {
        val m = frame(MessageCodec.MAX_MIRROR_FRAGMENT_BYTES)
        val back = roundTrip(m)
        assertArrayEquals(m.data, back.data)
        assertEquals(m.frameSequence, back.frameSequence)
        assertEquals(m.fragmentCount, back.fragmentCount)
    }

    @Test
    fun `fragment metadata survives encoding`() {
        val back = roundTrip(frame(1024, index = 3, count = 7, seq = 99))
        assertEquals(3, back.fragmentIndex)
        assertEquals(7, back.fragmentCount)
        assertEquals(99L, back.frameSequence)
    }

    @Test
    fun `a single-fragment frame decodes with the defaults`() {
        val back = roundTrip(frame(512))
        assertEquals(0, back.fragmentIndex)
        assertEquals(1, back.fragmentCount)
    }

    @Test
    fun `frames around the old 192 KiB cap split into fragments that each fit`() {
        val limit = MessageCodec.MAX_MIRROR_FRAGMENT_BYTES
        // 191, 192, 193 KiB and a large keyframe -- the boundary sizes the brief calls for.
        for (size in listOf(191 * 1024, 192 * 1024, 193 * 1024, 512 * 1024)) {
            val count = (size + limit - 1) / limit
            assertTrue("$size B needs $count fragments, over budget", count <= MessageCodec.MAX_MIRROR_FRAGMENTS)

            var sent = 0
            for (index in 0 until count) {
                val from = index * limit
                val slice = ByteArray(minOf(limit, size - from))
                val back = roundTrip(
                    MirrorFrame(UUID.randomUUID(), 0L, true, slice, 5L, index, count),
                )
                // Every fragment must encode and decode without a protocol error. Before
                // fragmentation, a single record of this size threw PAYLOAD_TOO_LARGE and the
                // session died.
                assertEquals(slice.size, back.data.size)
                sent += back.data.size
            }
            assertEquals("fragments must cover the whole frame", size, sent)
        }
    }

    @Test
    fun `the fragment budget bounds reassembly memory`() {
        val maxFrame = MessageCodec.MAX_MIRROR_FRAGMENT_BYTES * MessageCodec.MAX_MIRROR_FRAGMENTS
        assertEquals("16 x 64 KiB", 1024 * 1024, maxFrame)
    }
}
