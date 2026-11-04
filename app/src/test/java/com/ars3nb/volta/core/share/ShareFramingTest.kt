package com.ars3nb.volta.core.share

import com.ars3nb.volta.core.share.ShareFraming.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareFramingTest {

    @Test
    fun chunkSizeFollowsTheMtu() {
        assertEquals(16, ShareFraming.chunkSize(23))
        assertEquals(178, ShareFraming.chunkSize(185))
        // ATT caps one value at 512 bytes, whatever the MTU.
        assertEquals(508, ShareFraming.chunkSize(517))
        assertEquals(1, ShareFraming.chunkSize(5))
    }

    @Test
    fun everyFrameFitsOnePacket() {
        for (mtu in listOf(23, 64, 185, 247, 517)) {
            ShareFraming.split(ByteArray(3000), mtu).forEach { assertTrue(it.size <= minOf(mtu - 3, 512)) }
        }
    }

    @Test
    fun splitAndAssembleRoundTrip() {
        for (mtu in listOf(23, 185, 517)) {
            val chunk = ShareFraming.chunkSize(mtu)
            for (size in listOf(0, 1, chunk - 1, chunk, chunk + 1, 65, 5000)) {
                val message = ByteArray(size) { (it * 7 + size).toByte() }
                val frames = ShareFraming.split(message, mtu)
                assertEquals(maxOf(1, (size + chunk - 1) / chunk), frames.size)
                val asm = ShareFraming.Assembler(10_000)
                frames.dropLast(1).forEachIndexed { i, f ->
                    val step = asm.add(f)
                    assertEquals(Step.Partial(i + 1, frames.size), step)
                }
                val done = asm.add(frames.last())
                assertTrue("mtu $mtu size $size: $done", done is Step.Complete)
                assertArrayEquals(message, (done as Step.Complete).message)
            }
        }
    }

    @Test
    fun aRepeatedFrameIsIgnored() {
        val frames = ShareFraming.split(ByteArray(100) { it.toByte() }, 23)
        val asm = ShareFraming.Assembler(1000)
        asm.add(frames[0])
        asm.add(frames[1])
        assertEquals(Step.Partial(2, frames.size), asm.add(frames[1]))
        var last: Step? = null
        frames.drop(2).forEach { last = asm.add(it) }
        assertArrayEquals(ByteArray(100) { it.toByte() }, (last as Step.Complete).message)
    }

    @Test
    fun aMissingFrameBreaksTheMessage() {
        val frames = ShareFraming.split(ByteArray(100), 23)
        val asm = ShareFraming.Assembler(1000)
        asm.add(frames[0])
        assertTrue(asm.add(frames[2]) is Step.Broken)
        // After a break only a new start is accepted.
        assertTrue(asm.add(frames[3]) is Step.Broken)
        assertTrue(asm.add(frames[0]) is Step.Partial)
    }

    @Test
    fun malformedFramesAreRefused() {
        val asm = ShareFraming.Assembler(1000)
        assertTrue(asm.add(byteArrayOf(0, 0, 0)) is Step.Broken)
        assertTrue(asm.add(byteArrayOf(0, 0, 0, 0, 1)) is Step.Broken) // total 0
        assertTrue(asm.add(byteArrayOf(0, 2, 0, 2, 1)) is Step.Broken) // index ≥ total
        val a = ShareFraming.split(ByteArray(40), 23)
        val b = ShareFraming.split(ByteArray(80), 23)
        asm.add(a[0])
        assertTrue(asm.add(b[1]) is Step.Broken) // total changed mid-message
    }

    @Test
    fun aNewStartReplacesAHalfMessage() {
        val asm = ShareFraming.Assembler(1000)
        val first = ShareFraming.split(ByteArray(100) { 1 }, 23)
        asm.add(first[0])
        asm.add(first[1])
        val second = ShareFraming.split(ByteArray(20) { 2 }, 23)
        asm.add(second[0])
        val done = asm.add(second[1])
        assertArrayEquals(ByteArray(20) { 2 }, (done as Step.Complete).message)
    }

    @Test
    fun tooLargeIsSaidSo() {
        val asm = ShareFraming.Assembler(50)
        val frames = ShareFraming.split(ByteArray(100), 23)
        val steps = frames.map { asm.add(it) }
        assertTrue(steps.contains(Step.TooLarge))
    }
}
