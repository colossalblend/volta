package com.ars3nb.volta.core.ping

import com.ars3nb.volta.data.model.NetworkRegime
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkProbeTest {

    @Test
    fun probesTheHostsFromTheSpec() {
        assertEquals(listOf("google.com", "ars3nb.ru", "calculator888.ru"), NetworkProbe.HOME_HOSTS.map { it.first })
        assertEquals(listOf("vk.ru", "ya.ru", "appbank.pw"), NetworkProbe.LTE_HOSTS.map { it.first })
    }

    @Test
    fun onlyLteHostsAnsweringMeansLte() {
        assertEquals(NetworkRegime.WHITELIST_ONLY, NetworkProbe.classify(homeOk = 0, lteOk = 2))
    }

    @Test
    fun anyHomeHostAnsweringMeansHome() {
        assertEquals(NetworkRegime.OPEN, NetworkProbe.classify(homeOk = 1, lteOk = 3))
        assertEquals(NetworkRegime.OPEN, NetworkProbe.classify(homeOk = 1, lteOk = 0))
    }

    @Test
    fun silenceMeansNoNetwork() {
        assertEquals(NetworkRegime.BLOCKED, NetworkProbe.classify(homeOk = 0, lteOk = 0))
    }
}
