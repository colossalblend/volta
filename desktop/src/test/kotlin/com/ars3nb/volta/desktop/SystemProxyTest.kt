package com.ars3nb.volta.desktop

import com.ars3nb.volta.desktop.platform.SystemProxy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The proxy hand-over, on a pretend Windows: the user's settings always come back. */
class SystemProxyTest {

    private class FakeWindows(var now: SystemProxy.Snapshot) : SystemProxy.Api {
        var refuse = false
        override val available = true
        override fun read() = now
        override fun write(snapshot: SystemProxy.Snapshot): Boolean {
            if (refuse) return false
            now = snapshot
            return true
        }
    }

    private val users = SystemProxy.Snapshot(
        proxyEnable = 1,
        proxyServer = "corp-proxy:3128",
        proxyOverride = "<local>",
        autoConfigUrl = "http://wpad/proxy.pac",
        flags = 0x1 or 0x2 or 0x4
    )

    private fun tempFile(): File = Files.createTempDirectory("volta-proxy").toFile().resolve("backup.json")

    @Test
    fun appliesThenGivesTheUsersSettingsBack() {
        val win = FakeWindows(users)
        val backup = tempFile()
        val proxy = SystemProxy(backup, win)

        assertTrue(proxy.apply(10809))
        assertEquals("127.0.0.1:10809", win.now.proxyServer)
        assertEquals(1, win.now.proxyEnable)
        assertEquals(null, win.now.autoConfigUrl)
        assertTrue(proxy.isApplied())
        assertTrue(backup.isFile)

        assertTrue(proxy.restore())
        assertEquals(users, win.now)
        assertFalse(backup.exists())
    }

    @Test
    fun aReconnectKeepsTheOriginalBackup() {
        val win = FakeWindows(users)
        val proxy = SystemProxy(tempFile(), win)
        assertTrue(proxy.apply(10809))
        assertTrue(proxy.apply(20809))
        assertEquals("127.0.0.1:20809", win.now.proxyServer)
        assertTrue(proxy.restore())
        assertEquals(users, win.now)
    }

    @Test
    fun aCrashIsRepairedOnTheNextStart() {
        val win = FakeWindows(users)
        val backup = tempFile()
        SystemProxy(backup, win).apply(10809)
        // Volta died here. The next one starts:
        val next = SystemProxy(backup, win)
        assertTrue(next.recoverAfterCrash())
        assertEquals(users, win.now)
        assertFalse(backup.exists())
    }

    @Test
    fun aSettingTheUserChangedMeanwhileStays() {
        val win = FakeWindows(users)
        val proxy = SystemProxy(tempFile(), win)
        proxy.apply(10809)
        val theirs = SystemProxy.Snapshot(proxyEnable = 1, proxyServer = "other:8080")
        win.now = theirs
        assertTrue(proxy.restore())
        assertEquals(theirs, win.now)
    }

    @Test
    fun aRefusalLeavesNothingBehind() {
        val win = FakeWindows(SystemProxy.Snapshot(proxyEnable = 0))
        win.refuse = true
        val backup = tempFile()
        val proxy = SystemProxy(backup, win)
        assertFalse(proxy.apply(10809))
        assertEquals(0, win.now.proxyEnable)
    }
}
