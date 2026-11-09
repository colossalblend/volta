package com.ars3nb.volta.desktop

import org.junit.Test

/** The packaged app's offline self-checks hold here first (SelfTestFixtures). */
class SelfTestFixturesTest {
    @Test
    fun geminiFixtures() {
        SelfTestFixtures.gemini()
    }

    @Test
    fun trafficSummary() {
        SelfTestFixtures.traffic()
    }

    @Test
    fun raceJudge() {
        SelfTestFixtures.raceJudge()
    }
}
