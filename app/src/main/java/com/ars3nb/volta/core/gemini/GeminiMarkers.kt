package com.ars3nb.volta.core.gemini

/**
 * Everything the Gemini check reads out of gemini.google.com, in one place: when Google
 * changes its page, this is the only file to update (and GeminiPageTest the fixtures).
 *
 * Where the markers come from (checked November 2026):
 *  - lmc999/RegionRestrictionCheck `check.sh`, WebTest_Gemini: GET https://gemini.google.com
 *    with a desktop Chrome User-Agent, available when the HTML holds `45631641,null,true`;
 *    the region is the three letters in `,2,1,200,"XXX"`.
 *  - Google has since moved the same switch from that array form into an object,
 *    `\"45631641\":true` inside the escaped JSON of an inline script. The maintained ports
 *    accept both: qsoyq/stash media-unlock-checker.js (`45631641(?:,null,true|\\?"\s*:\s*true)`),
 *    ZeroDeng01/sublinkPro checker_gemini.go (October 2026), iluobei/miaomiaowuX 0.5.6,
 *    whose notes add that only the switch set to false means "not available"; with no
 *    switch at all the page is "not recognised", not "not available".
 *  - oneclickvirt/UnlockTests Gemini.go also takes `45617354,null,true` as available.
 *  - The countries Gemini is not offered in: Google's "Where you can use the Gemini web
 *    app" (support.google.com/gemini/answer/13575153) lists where it is; mainland China is
 *    Workspace only, and Russia, Belarus, Iran, North Korea, Cuba and Syria are not on it.
 *    The same set qsoyq/stash falls back on.
 */
object GeminiMarkers {

    const val URL = "https://gemini.google.com/"

    /** The User-Agent the region checkers send; Google serves the full page to it. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /** English first, so the "not available" sentences below can be matched as well. */
    const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

    const val ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    /**
     * "Reject all" on Google's cookie consent, as a browser that answered it once would
     * send: keeps a European exit from landing on consent.google.com instead of Gemini.
     */
    const val CONSENT_COOKIE_NAME = "SOCS"
    const val CONSENT_COOKIE_VALUE = "CAI"
    const val CONSENT_COOKIE_DOMAIN = "google.com"

    /** The page is ~30 KB; the markers sit in its first scripts. Anything more is not read. */
    const val BODY_LIMIT_BYTES = 300 * 1024

    /** The whole request: connect, redirects, the page. */
    const val TIMEOUT_MS = 10_000L

    /** The availability switch in Gemini's start-up flags. */
    const val FLAG = "45631641"

    /** Taken as "available" too by oneclickvirt/UnlockTests; only ever as a fallback here. */
    const val FLAG_FALLBACK = "45617354"

    /**
     * The switch in either form: `45631641,null,true` (an array) or `"45631641":true`
     * (an object, its quotes escaped once or more inside a script string).
     */
    private fun switch(flag: String, value: String) =
        Regex("""(?:(?<![0-9])|(?<=\\u0022))$flag(?:,null,$value\b|(?:\\*"|\\u0022)\s*:\s*$value\b)""")

    val FLAG_ON = switch(FLAG, "true")
    val FLAG_OFF = switch(FLAG, "false")
    val FALLBACK_ON = switch(FLAG_FALLBACK, "true")

    /** The country Google placed the request in, ISO 3166-1 alpha-3 ("USA"). */
    val REGION = Regex(""",2,1,200,(?:\\*"|\\u0022)([A-Z]{3})(?:\\*"|\\u0022)""")

    /** What Gemini says, in English, where it is not offered. Matched in lower case. */
    val NOT_AVAILABLE_TEXT = listOf(
        "gemini isn't available in your country",
        "gemini is not available in your country",
        "gemini isn't currently supported in your country",
        "gemini is not currently supported in your country",
        "gemini isn't available in your region",
        "gemini is not available in your region",
        "isn't currently supported in your country",
        "not available in your country",
        "not available in your region"
    )

    /** Regions (alpha-3) where the web app is not offered: a fallback when no switch is found. */
    val UNSUPPORTED_REGIONS = setOf("CHN", "RUS", "BLR", "IRN", "PRK", "CUB", "SYR")

    /** Google's cookie wall: nothing can be said about Gemini behind it. */
    const val CONSENT_HOST = "consent.google.com"
    val CONSENT_TEXT = listOf("consent.google.com/save", "before you continue to google")

    /** Google refusing the exit's address ("unusual traffic", a captcha on /sorry/). */
    const val CAPTCHA_PATH = "/sorry/"
    val CAPTCHA_TEXT = listOf("unusual traffic from your computer network", "our systems have detected unusual traffic")

    /** Signs that this is Gemini's own page at all, not something else that answered. */
    val GEMINI_PAGE_TEXT = listOf("WIZ_global_data", "BardChatUi", "gemini.google.com")
}
