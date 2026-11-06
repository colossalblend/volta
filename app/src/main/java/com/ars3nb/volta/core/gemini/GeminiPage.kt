package com.ars3nb.volta.core.gemini

import java.util.Locale

/** What a Gemini check found out about one server. */
enum class GeminiStatus {
    /** gemini.google.com, opened through the server, says Gemini is on there. */
    AVAILABLE,

    /** Gemini answered through the server and is not offered in its country. */
    UNSUPPORTED_REGION,

    /** No verdict: the request failed, Google blocked it, or the page was not recognised. */
    BLOCKED_OR_ERROR
}

/** Why the verdict is what it is: what the screen may add in small print. */
enum class GeminiReason {
    FLAG_ON,
    FLAG_OFF,
    NOT_AVAILABLE_TEXT,
    UNSUPPORTED_REGION_LIST,
    LEGAL_BLOCK,
    CONSENT,
    CAPTCHA,
    FORBIDDEN,
    HTTP_ERROR,
    UNRECOGNIZED,
    NETWORK,
    TIMEOUT,

    /** The server runs in a helper of its own: only while connected to it. */
    NEEDS_CONNECTION,

    /** This build cannot open a connection through the server at all. */
    CANNOT_DIAL
}

/**
 * One check's outcome. [region] is the alpha-3 code Google placed the request in, when
 * the page named one; [checkedAt] when it was made, for the cache.
 */
data class GeminiResult(
    val status: GeminiStatus,
    val reason: GeminiReason,
    val region: String? = null,
    val httpCode: Int = 0,
    val checkedAt: Long = 0
) {
    /** [region] as an alpha-2 code ("US"), for a flag and a name; null when unknown. */
    val regionAlpha2: String? get() = region?.let(GeminiPage::alpha2)

    fun isFresh(now: Long, maxAgeMs: Long): Boolean = now - checkedAt in 0..maxAgeMs
}

/**
 * Reads gemini.google.com's answer. Pure: the code and the text in, the verdict out, so
 * the rules can be tested on saved pages. The markers are in [GeminiMarkers].
 *
 * Only a definite sign says "works" or "doesn't": the switch itself, Gemini's own words,
 * a country where it is known not to be offered, or a 451. Anything else (a cookie wall,
 * a captcha, a page that is not Gemini's) is "couldn't check", never a guess.
 */
object GeminiPage {

    fun evaluate(httpCode: Int, finalUrl: String, body: String): GeminiResult {
        val url = finalUrl.lowercase(Locale.ROOT)
        val lower = body.lowercase(Locale.ROOT)
        val region = GeminiMarkers.REGION.find(body)?.groupValues?.get(1)

        fun result(status: GeminiStatus, reason: GeminiReason) =
            GeminiResult(status, reason, region, httpCode)

        val flagOn = GeminiMarkers.FLAG_ON.containsMatchIn(body)
        val captcha = GeminiMarkers.CAPTCHA_PATH in url ||
            (!flagOn && GeminiMarkers.CAPTCHA_TEXT.any { it in lower })
        if (captcha) return result(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CAPTCHA)
        if (httpCode == 451) return result(GeminiStatus.UNSUPPORTED_REGION, GeminiReason.LEGAL_BLOCK)
        if (httpCode == 403) return result(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.FORBIDDEN)
        if (httpCode !in 200..299) return result(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.HTTP_ERROR)

        val consent = hostOf(url) == GeminiMarkers.CONSENT_HOST ||
            GeminiMarkers.CONSENT_TEXT.any { it in lower }
        if (consent && !flagOn) {
            return result(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CONSENT)
        }

        return when {
            flagOn ->
                result(GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON)
            GeminiMarkers.FLAG_OFF.containsMatchIn(body) ->
                result(GeminiStatus.UNSUPPORTED_REGION, GeminiReason.FLAG_OFF)
            GeminiMarkers.NOT_AVAILABLE_TEXT.any { it in lower } ->
                result(GeminiStatus.UNSUPPORTED_REGION, GeminiReason.NOT_AVAILABLE_TEXT)
            region != null && region in GeminiMarkers.UNSUPPORTED_REGIONS ->
                result(GeminiStatus.UNSUPPORTED_REGION, GeminiReason.UNSUPPORTED_REGION_LIST)
            GeminiMarkers.FALLBACK_ON.containsMatchIn(body) ->
                result(GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON)
            else -> result(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.UNRECOGNIZED)
        }
    }

    /** The request itself failed: nothing came back to read. */
    fun failed(timeout: Boolean): GeminiResult = GeminiResult(
        GeminiStatus.BLOCKED_OR_ERROR,
        if (timeout) GeminiReason.TIMEOUT else GeminiReason.NETWORK
    )

    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@')
            .substringBefore(':')

    private val alpha3To2: Map<String, String> by lazy {
        Locale.getISOCountries().mapNotNull { code ->
            runCatching { Locale("", code).isO3Country }.getOrNull()
                ?.takeIf { it.length == 3 }
                ?.let { it.uppercase(Locale.ROOT) to code }
        }.toMap()
    }

    /** "USA" → "US"; null for a code no country has. */
    fun alpha2(alpha3: String): String? = alpha3To2[alpha3.uppercase(Locale.ROOT)]
}
