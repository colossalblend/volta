package com.ars3nb.volta.core.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict rules on snippets shaped like what gemini.google.com sends: its start-up
 * flags in both the old array form and the newer object form, the OneGoogle region field,
 * and the pages Google shows instead of Gemini (consent, captcha, sign-in).
 */
class GeminiPageTest {

    private val home = "https://gemini.google.com/"

    /** The head of the page the way Gemini wraps its flags: escaped JSON inside a script. */
    private fun page(flags: String, region: String? = "USA") = buildString {
        append("<!doctype html><html lang=\"en\"><head><title>Gemini</title>")
        append("<script nonce=\"x\">window.WIZ_global_data = {\"Im6cmf\":\"/_/BardChatUi\",")
        append("\"TSDtV\":\"%.@.[[null,[[45459555,null,false,null,null,null,\\\"Imeoqb\\\"]]]]\",")
        append(flags)
        append("};</script>")
        if (region != null) append("<script>var og=[null,[1,2,3],2,1,200,\"$region\",null,\"en\"];</script>")
        append("</head><body><c-wiz></c-wiz></body></html>")
    }

    // ------------------------------------------------------------------ available

    @Test
    fun objectFormSwitchOnIsAvailableWithItsRegion() {
        val r = GeminiPage.evaluate(200, home, page("\"nQyAE\":{\\\"45631641\\\":true,\\\"4561\\\":false}"))
        assertEquals(GeminiStatus.AVAILABLE, r.status)
        assertEquals(GeminiReason.FLAG_ON, r.reason)
        assertEquals("USA", r.region)
        assertEquals("US", r.regionAlpha2)
    }

    @Test
    fun arrayFormSwitchOnIsAvailable() {
        val r = GeminiPage.evaluate(
            200, home, page("\"TSDtV2\":\"%.@.[[null,[[45631641,null,true,null,null,null,\\\"q\\\"]]]]\"", region = "DEU")
        )
        assertEquals(GeminiStatus.AVAILABLE, r.status)
        assertEquals("DE", r.regionAlpha2)
    }

    @Test
    fun unescapedJsonAndDoubleEscapedSwitchesAreReadToo() {
        assertEquals(
            GeminiStatus.AVAILABLE,
            GeminiPage.evaluate(200, home, "{\"45631641\": true}").status
        )
        assertEquals(
            GeminiStatus.AVAILABLE,
            GeminiPage.evaluate(200, home, "x\\\\\\\"45631641\\\\\\\":true y").status
        )
    }

    @Test
    fun theSwitchWinsOverAStrayNotAvailableSentence() {
        val body = page("\\\"45631641\\\":true") + "<p>Some features are not available in your country</p>"
        assertEquals(GeminiStatus.AVAILABLE, GeminiPage.evaluate(200, home, body).status)
    }

    @Test
    fun theFallbackSwitchCountsOnlyWhenTheMainOneIsMissing() {
        assertEquals(
            GeminiStatus.AVAILABLE,
            GeminiPage.evaluate(200, home, page("\"a\":\"[[45617354,null,true]]\"", region = null)).status
        )
        // The main switch off in a country Gemini is offered in: no verdict either way.
        assertEquals(
            GeminiReason.UNSURE,
            GeminiPage.evaluate(200, home, page("\"a\":\"[[45617354,null,true],[45631641,null,false]]\"")).reason
        )
    }

    // ------------------------------------------------------------------ not offered there

    @Test
    fun switchOffIsUnsupportedRegion() {
        val r = GeminiPage.evaluate(200, home, page("\"nQyAE\":{\\\"45631641\\\":false}", region = "RUS"))
        assertEquals(GeminiStatus.UNSUPPORTED_REGION, r.status)
        assertEquals(GeminiReason.UNSUPPORTED_REGION_LIST, r.reason)
        assertEquals("RU", r.regionAlpha2)
    }

    @Test
    fun arrayFormSwitchOffIsUnsupportedRegion() {
        val r = GeminiPage.evaluate(200, home, page("\"x\":\"[[45631641,null,false]]\"", region = "CHN"))
        assertEquals(GeminiStatus.UNSUPPORTED_REGION, r.status)
        assertEquals(GeminiReason.UNSUPPORTED_REGION_LIST, r.reason)
    }

    @Test
    fun geminisOwnWordsMeanUnsupported() {
        val body = "<html><body><h1>Gemini isn't currently supported in your country. Stay tuned!</h1></body></html>"
        val r = GeminiPage.evaluate(200, home, body)
        assertEquals(GeminiStatus.UNSUPPORTED_REGION, r.status)
        assertEquals(GeminiReason.NOT_AVAILABLE_TEXT, r.reason)
    }

    @Test
    fun noSwitchButAnUnsupportedRegionIsUnsupported() {
        val r = GeminiPage.evaluate(200, home, page("\"z\":1", region = "IRN"))
        assertEquals(GeminiStatus.UNSUPPORTED_REGION, r.status)
        assertEquals(GeminiReason.UNSUPPORTED_REGION_LIST, r.reason)
        assertEquals("IR", r.regionAlpha2)
    }

    @Test
    fun legalBlockIsUnsupported() {
        assertEquals(GeminiStatus.UNSUPPORTED_REGION, GeminiPage.evaluate(451, home, "").status)
    }

    // ------------------------------------------------------------------ couldn't check

    @Test
    fun noSwitchOnGeminisPageInASupportedRegionWorks() {
        val r = GeminiPage.evaluate(200, home, page("\"z\":1", region = "NLD"))
        assertEquals(GeminiStatus.AVAILABLE, r.status)
        assertEquals(GeminiReason.SUPPORTED_REGION, r.reason)
        assertEquals("NLD", r.region)
    }

    @Test
    fun aRegionOnAPageThatIsNotGeminisIsNotAVerdict() {
        val r = GeminiPage.evaluate(200, home, "<html>,2,1,200,\"DEU\"</html>")
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, r.status)
    }

    // The false "doesn't work" seen on a German exit where Gemini opened in the browser.
    @Test
    fun anOtherFeaturesNotAvailableStringIsNotAVerdict() {
        val body = page("\"z\":1", region = null) + "<p>This feature is not available in your country</p>"
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, GeminiPage.evaluate(200, home, body).status)
        val inGermany = page("\"z\":1", region = "DEU") + "<p>Gemini isn't available in your country</p>"
        assertEquals(GeminiStatus.AVAILABLE, GeminiPage.evaluate(200, home, inGermany).status)
    }

    @Test
    fun switchOffWithNoCountryIsUnsureNotUnsupported() {
        val r = GeminiPage.evaluate(200, home, page("\"nQyAE\":{\\\"45631641\\\":false}", region = null))
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, r.status)
        assertEquals(GeminiReason.UNSURE, r.reason)
    }

    @Test
    fun verdictsOfTheFirstRulesAreForgotten() {
        val json = GeminiChecks.encode(
            mapOf(
                1L to GeminiResult(GeminiStatus.UNSUPPORTED_REGION, GeminiReason.FLAG_OFF, null, 200, 5),
                2L to GeminiResult(GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON, "USA", 200, 5)
            ),
            now = 10
        )
        assertEquals(setOf(2L), GeminiChecks.decode(json).keys)
    }

    @Test
    fun consentPageIsNotAVerdict() {
        val body = "<html><head><title>Before you continue to Google</title></head>" +
            "<body><form action=\"https://consent.google.com/save\" method=\"POST\"></form></body></html>"
        val r = GeminiPage.evaluate(200, "https://consent.google.com/m?continue=https://gemini.google.com/&gl=DE", body)
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, r.status)
        assertEquals(GeminiReason.CONSENT, r.reason)
    }

    @Test
    fun captchaIsBlockedNotUnsupported() {
        val body = "<html><body>Our systems have detected unusual traffic from your computer network.</body></html>"
        val r = GeminiPage.evaluate(429, "https://www.google.com/sorry/index?continue=https://gemini.google.com/", body)
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, r.status)
        assertEquals(GeminiReason.CAPTCHA, r.reason)
    }

    @Test
    fun aRedirectToSignInIsUnrecognized() {
        val body = "<html><head><title>Sign in - Google Accounts</title></head><body>Email or phone</body></html>"
        val r = GeminiPage.evaluate(200, "https://accounts.google.com/ServiceLogin?continue=https://gemini.google.com/app", body)
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, r.status)
        assertEquals(GeminiReason.UNRECOGNIZED, r.reason)
    }

    @Test
    fun forbiddenAndServerErrorsAreNoVerdict() {
        val forbidden = GeminiPage.evaluate(403, home, "<html>403. That's an error.</html>")
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, forbidden.status)
        assertEquals(GeminiReason.FORBIDDEN, forbidden.reason)
        val broken = GeminiPage.evaluate(502, home, "Bad gateway")
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, broken.status)
        assertEquals(GeminiReason.HTTP_ERROR, broken.reason)
        assertEquals(502, broken.httpCode)
    }

    @Test
    fun garbageAndEmptyPagesAreNoVerdict() {
        listOf("", "<html>hello</html>", "\u0000\u0001binary", "{\"45631641x\":true}", "945631641,null,true", "456316410,null,true")
            .forEach { body ->
                val r = GeminiPage.evaluate(200, home, body)
                assertEquals(body, GeminiStatus.BLOCKED_OR_ERROR, r.status)
                assertNull(body, r.region)
            }
    }

    @Test
    fun aFailedRequestIsNoVerdict() {
        assertEquals(GeminiReason.TIMEOUT, GeminiPage.failed(timeout = true).reason)
        assertEquals(GeminiReason.NETWORK, GeminiPage.failed(timeout = false).reason)
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, GeminiPage.failed(timeout = false).status)
    }

    // ------------------------------------------------------------------ region, freshness

    @Test
    fun regionCodesMapToTwoLetters() {
        assertEquals("US", GeminiPage.alpha2("USA"))
        assertEquals("GB", GeminiPage.alpha2("gbr"))
        assertNull(GeminiPage.alpha2("XYZ"))
    }

    @Test
    fun anEscapedRegionFieldIsReadToo() {
        val r = GeminiPage.evaluate(200, home, "\\\"45631641\\\":true ,2,1,200,\\\"JPN\\\"")
        assertEquals("JPN", r.region)
    }

    @Test
    fun freshnessIsMeasuredFromTheCheck() {
        val r = GeminiResult(GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON, checkedAt = 1_000)
        assertTrue(r.isFresh(now = 2_000, maxAgeMs = 5_000))
        assertFalse(r.isFresh(now = 10_000, maxAgeMs = 5_000))
        // A clock that went back does not make an old verdict look new.
        assertFalse(r.isFresh(now = 500, maxAgeMs = 5_000))
    }
}
