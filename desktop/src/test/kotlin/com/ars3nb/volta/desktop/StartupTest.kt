package com.ars3nb.volta.desktop

import com.ars3nb.volta.desktop.data.Store
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Start-up: the single-instance answer, safe graphics after a failed start, the saved state. */
class StartupTest {

    private fun tempPaths(name: String) = AppPaths(Files.createTempDirectory(name).toFile()).apply { ensure() }

    @Test
    fun aRunningVoltaAnswersASecondStart() {
        val paths = tempPaths("volta-instance")
        assertTrue(SingleInstance.acquire(paths))
        val got = mutableListOf<List<String>>()
        SingleInstance.listen(paths) { got += it; true }
        assertEquals(SingleInstance.Sent.ACKED, SingleInstance.send(paths, listOf("volta://import/abc", "--x")))
        assertEquals(listOf("volta://import/abc", "--x"), got.single())
    }

    @Test
    fun aHungVoltaIsToldApartFromAnAbsentOne() {
        val absent = tempPaths("volta-absent")
        assertEquals(SingleInstance.Sent.NOT_LISTENING, SingleInstance.send(absent, emptyList()))

        // The listener of another process whose UI never takes the message.
        val hung = tempPaths("volta-hung")
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        File(hung.dataDir, "instance.port").writeText(server.localPort.toString())
        Thread {
            val c = server.accept()
            Thread.sleep(SingleInstance.ACK_TIMEOUT_MS + 2_000L)
            c.close()
        }.apply { isDaemon = true }.start()
        val started = System.currentTimeMillis()
        assertEquals(SingleInstance.Sent.NO_ANSWER, SingleInstance.send(hung, listOf("x")))
        assertTrue(System.currentTimeMillis() - started < SingleInstance.ACK_TIMEOUT_MS + 1_500)
        server.close()
    }

    @Test
    fun aStartThatNeverShowedItsWindowMeansSafeGraphicsNextTime() {
        val paths = tempPaths("volta-graphics")
        val before = System.getProperty("skiko.renderApi")
        try {
            assertFalse(GraphicsMode.isSafe(paths))
            GraphicsMode.arm(paths)
            // ...and the process died before disarm().
            GraphicsMode.prepare(paths)
            assertTrue(GraphicsMode.isSafe(paths))
            assertTrue(GraphicsMode.isAuto(paths))
            assertEquals("SOFTWARE", System.getProperty("skiko.renderApi"))
            // The switch in Settings turns it off again.
            GraphicsMode.setSafe(paths, false)
            assertFalse(GraphicsMode.isSafe(paths))
            // A start that did show its window leaves nothing behind.
            GraphicsMode.arm(paths)
            GraphicsMode.disarm(paths)
            System.clearProperty("skiko.renderApi")
            GraphicsMode.prepare(paths)
            assertFalse(GraphicsMode.isSafe(paths))
        } finally {
            if (before == null) System.clearProperty("skiko.renderApi") else System.setProperty("skiko.renderApi", before)
        }
    }

    @Test
    fun aPartlyUnreadableStateKeepsWhatStillReads() {
        val dir = Files.createTempDirectory("volta-salvage").toFile()
        val file = File(dir, "volta.json")
        file.writeText(
            """{"servers":[{"id":7,"remark":"X","address":"x.example","port":1},{"id":"not-a-number","remark":"Y"}],""" +
                """"nextId":{"shape":"changed"},"settings":{"language":"ru"}}"""
        )
        val store = Store(file)
        assertEquals("x.example", store.value.servers.single().address)
        assertEquals("ru", store.value.settings.language)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("volta.json.unreadable-") })
    }

    @Test
    fun aBrokenStateComesBackFromTheLastGoodCopy() {
        val dir = Files.createTempDirectory("volta-bak").toFile()
        val file = File(dir, "volta.json")
        file.writeText("""{"servers":[{"id":3,"remark":"DE","address":"de.example","port":443}]}""")
        Store(file) // a good start leaves volta.json.bak
        assertTrue(File(dir, "volta.json.bak").isFile)
        file.writeText("{ truncated")
        val store = Store(file)
        assertEquals("de.example", store.value.servers.single().address)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("volta.json.broken-") })
    }
}
