package com.ars3nb.volta.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {

    private val sha = "ab".repeat(32)

    /** update.json exactly as CI writes it. */
    private val manifest = """
        {
          "versionCode": 112,
          "versionName": "1.1.12",
          "commit": "0bef4f3dcbd7595f91e178903483f2afd8d33314",
          "branch": "redesign-auto-groups",
          "xray": "v1.260327.1-0.20261021115930-1b1647cd9ddf",
          "apks": [
            {"abi": "arm64-v8a-debug", "name": "volta-0bef4f3-arm64-v8a-debug.apk", "size": 65044498, "sha256": "$sha"},
            {"abi": "arm64-v8a", "name": "volta-0bef4f3-arm64-v8a.apk", "size": 44091092, "sha256": "$sha"},
            {"abi": "armeabi-v7a", "name": "volta-0bef4f3-armeabi-v7a.apk", "size": 43259228, "sha256": "$sha"},
            {"abi": "universal", "name": "volta-0bef4f3-universal.apk", "size": 146491348, "sha256": "$sha"},
            {"abi": "x86_64", "name": "volta-0bef4f3-x86_64.apk", "size": 46400512, "sha256": "$sha"}
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheManifestCiWrites() {
        val m = AppUpdater.parseManifest(manifest)!!
        assertEquals(112, m.versionCode)
        assertEquals("1.1.12", m.versionName)
        assertEquals(5, m.apks.size)
        assertEquals(44091092L, m.apks[1].size)
    }

    @Test
    fun brokenManifestIsNoUpdate() {
        assertNull(AppUpdater.parseManifest("<html>Not Found</html>"))
        assertNull(AppUpdater.parseManifest("""{"versionName": "1.1.12"}"""))
    }

    @Test
    fun picksTheApkForThePhonesCpu() {
        val apks = AppUpdater.parseManifest(manifest)!!.apks
        val phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
        assertEquals("arm64-v8a", AppUpdater.pickApk(apks, phone, debug = false)?.abi)
        assertEquals("arm64-v8a-debug", AppUpdater.pickApk(apks, phone, debug = true)?.abi)
        assertEquals("armeabi-v7a", AppUpdater.pickApk(apks, listOf("armeabi-v7a", "armeabi"), debug = true)?.abi)
        assertEquals("universal", AppUpdater.pickApk(apks, listOf("riscv64"), debug = false)?.abi)
    }

    @Test
    fun neverTakesAnApkWithoutAChecksum() {
        val apks = listOf(
            UpdateApk("arm64-v8a", "a.apk", 10, ""),
            UpdateApk("universal", "u.apk", 30, sha)
        )
        assertEquals("u.apk", AppUpdater.pickApk(apks, listOf("arm64-v8a"), debug = false)?.name)
        assertNull(AppUpdater.pickApk(apks.take(1), listOf("arm64-v8a"), debug = false))
    }

    @Test
    fun readsAssetLinksFromTheReleaseApi() {
        val release = """
            {"tag_name": "apk-main", "assets": [
              {"name": "update.json", "url": "https://api.github.com/repos/o/r/releases/assets/1"},
              {"name": "volta-x-arm64-v8a.apk", "url": "https://api.github.com/repos/o/r/releases/assets/2"}
            ]}
        """.trimIndent()
        val assets = AppUpdater.parseAssets(release)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/1", assets["update.json"])
        assertEquals(2, assets.size)
        assertEquals(emptyMap<String, String>(), AppUpdater.parseAssets("{}"))
    }

    @Test
    fun followsItsBranchAndMain() {
        assertEquals(listOf("apk-redesign-auto-groups", "apk-main"), AppUpdater.channels("apk-redesign-auto-groups"))
        assertEquals(listOf("apk-main"), AppUpdater.channels("apk-main"))
    }
}
