package ai.aneesiqbal.rozan.airplay.android.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PreSurfaceBufferTest {
    private fun unit(
        tag: Int,
        size: Int = 8,
    ) = ByteArray(size) { tag.toByte() }

    private fun drain(buffer: PreSurfaceBuffer): List<Pair<Long, Byte>> {
        val out = ArrayList<Pair<Long, Byte>>()
        val dst = ByteBuffer.allocate(1 shl 16)
        while (!buffer.isEmpty) {
            dst.clear()
            val pts = buffer.peekPresentationNanos()
            buffer.copyPeek(dst)
            out += pts to dst.get(0)
            buffer.poll()
        }
        return out
    }

    @Test
    fun dropsFramesBeforeTheFirstKeyFrame() {
        val buffer = PreSurfaceBuffer(maxBytes = 1024, maxUnits = 16, initialBytes = 64)
        assertFalse(buffer.add(unit(1), 0, 8, 10, keyFrame = false))
        assertTrue(buffer.awaitingKeyFrame)
        assertTrue(buffer.add(unit(2), 0, 8, 20, keyFrame = true))
        assertTrue(buffer.add(unit(3), 0, 8, 30, keyFrame = false))
        assertEquals(listOf(20L to 2.toByte(), 30L to 3.toByte()), drain(buffer))
        assertEquals(1L, buffer.droppedUnits)
    }

    @Test
    fun aNewKeyFrameDiscardsTheOlderGroup() {
        val buffer = PreSurfaceBuffer(maxBytes = 1024, maxUnits = 16, initialBytes = 64)
        buffer.add(unit(1), 0, 8, 10, keyFrame = true)
        buffer.add(unit(2), 0, 8, 20, keyFrame = false)
        buffer.add(unit(3), 0, 8, 30, keyFrame = true)
        buffer.add(unit(4), 0, 8, 40, keyFrame = false)
        assertEquals(40L, buffer.lastPresentationNanos())
        assertTrue(buffer.peekKeyFrame())
        assertEquals(listOf(30L to 3.toByte(), 40L to 4.toByte()), drain(buffer))
    }

    @Test
    fun overflowEmptiesAndWaitsForTheNextKeyFrame() {
        val buffer = PreSurfaceBuffer(maxBytes = 64, maxUnits = 16, initialBytes = 16)
        assertTrue(buffer.add(unit(1, 32), 0, 32, 10, keyFrame = true))
        assertTrue(buffer.add(unit(2, 24), 0, 24, 20, keyFrame = false))
        assertFalse(buffer.add(unit(3, 24), 0, 24, 30, keyFrame = false))
        assertTrue(buffer.isEmpty)
        assertTrue(buffer.awaitingKeyFrame)
        assertFalse(buffer.add(unit(4), 0, 8, 40, keyFrame = false))
        assertTrue(buffer.add(unit(5), 0, 8, 50, keyFrame = true))
        assertEquals(listOf(50L to 5.toByte()), drain(buffer))
    }

    @Test
    fun unitCountIsBounded() {
        val buffer = PreSurfaceBuffer(maxBytes = 1 shl 20, maxUnits = 4, initialBytes = 64)
        buffer.add(unit(1), 0, 8, 1, keyFrame = true)
        repeat(3) { assertTrue(buffer.add(unit(2), 0, 8, 2, keyFrame = false)) }
        assertFalse(buffer.add(unit(3), 0, 8, 3, keyFrame = false))
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun growsOnDemandUpToTheBoundAndKeepsContent() {
        val buffer = PreSurfaceBuffer(maxBytes = 4096, maxUnits = 64, initialBytes = 16)
        val key = ByteArray(1000) { (it % 251).toByte() }
        assertTrue(buffer.add(key, 0, key.size, 1, keyFrame = true))
        assertTrue(buffer.capacityBytes >= 1000)
        assertTrue(buffer.capacityBytes <= 4096)
        val dst = ByteArray(1000)
        assertEquals(1000, buffer.copyPeek(dst))
        assertArrayEquals(key, dst)
    }

    @Test
    fun drainingWhileAddingKeepsOrderAcrossCompaction() {
        val buffer = PreSurfaceBuffer(maxBytes = 64, maxUnits = 8, initialBytes = 64)
        buffer.add(unit(0, 16), 0, 16, 0, keyFrame = true)
        val seen = ArrayList<Long>()
        val dst = ByteBuffer.allocate(64)
        for (i in 1..40) {
            assertTrue("unit $i", buffer.add(unit(i, 16), 0, 16, i.toLong(), keyFrame = false))
            dst.clear()
            seen += buffer.peekPresentationNanos()
            buffer.copyPeek(dst)
            assertEquals(seen.last().toByte(), dst.get(0))
            buffer.poll()
        }
        assertEquals((0L..39L).toList(), seen)
    }

    @Test
    fun resetCanKeepAcceptingNonKeyFrames() {
        val buffer = PreSurfaceBuffer(maxBytes = 1024, maxUnits = 8, initialBytes = 64)
        buffer.add(unit(1), 0, 8, 1, keyFrame = true)
        buffer.reset(awaitKeyFrame = false)
        assertTrue(buffer.isEmpty)
        assertTrue(buffer.add(unit(2), 0, 8, 2, keyFrame = false))
        buffer.reset(awaitKeyFrame = true)
        assertFalse(buffer.add(unit(3), 0, 8, 3, keyFrame = false))
    }
}
