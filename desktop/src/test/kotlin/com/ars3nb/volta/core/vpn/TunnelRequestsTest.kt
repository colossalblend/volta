package com.ars3nb.volta.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Android app's switching rules (TunnelRequestsTest there), on Windows. */
class TunnelRequestsTest {

    @Test
    fun aFailedPickByHandGoesBackToTheNodeThatWorked() {
        assertEquals(Fallback.BACK, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = 7))
    }

    @Test
    fun anythingElseMovesOnToTheNextBest() {
        // The auto pick and the guard choose by themselves: the next best node.
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.AUTO, failedId = 8, lastGoodId = 7))
        // The way back failed too: no ping-pong between two dead nodes.
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.FALLBACK, failedId = 7, lastGoodId = 7))
        // Nothing worked before it, or what worked is the very node that failed.
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = null))
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = 8))
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = 0))
    }

    @Test
    fun anotherNodeOnALiveTunnelShowsAsSwitchingFromTheFirstMoment() {
        val moving = TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = null, nextId = 8, nextName = "DE")
        assertEquals(TunnelRequests.Switching(7, "NL", 8, "DE"), moving)
    }

    @Test
    fun theLiveNodeAgainIsNoSwitchUnlessAMoveWasUnderWay() {
        // Asked for what already runs (new settings): nothing moves.
        assertNull(TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = null, nextId = 7, nextName = "NL"))
        // NL → DE was on its way and NL is clicked again: moving back.
        val away = TunnelRequests.Switching(7, "NL", 8, "DE")
        assertEquals(
            TunnelRequests.Switching(8, "DE", 7, "NL"),
            TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = away, nextId = 7, nextName = "NL")
        )
    }

    @Test
    fun theLatestClickNamesTheTarget() {
        // NL live, DE on its way, then FR: the screen says FR.
        val away = TunnelRequests.Switching(7, "NL", 8, "DE")
        assertEquals(
            TunnelRequests.Switching(7, "NL", 9, "FR"),
            TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = away, nextId = 9, nextName = "FR")
        )
        // The same target again keeps the mark as it is.
        assertEquals(away, TunnelRequests.switchingFor(7, "NL", away, 8, "DE"))
    }
}
