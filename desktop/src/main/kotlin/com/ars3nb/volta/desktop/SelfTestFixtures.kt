package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.balancer.RaceJudge
import com.ars3nb.volta.core.gemini.GeminiPage
import com.ars3nb.volta.core.gemini.GeminiReason
import com.ars3nb.volta.core.gemini.GeminiStatus
import com.ars3nb.volta.core.stats.TrafficDayEntity
import com.ars3nb.volta.core.stats.TrafficTally
import com.ars3nb.volta.data.model.ServerEntity
import java.time.LocalDate

/**
 * The self-test's checks that need no network: the same rules the unit tests cover,
 * run once more inside the packaged app, on its own Java runtime (cf. GeminiPageTest,
 * TrafficTallyTest, RaceJudgeTest). Each returns a line for the log or throws.
 */
internal object SelfTestFixtures {

    /** The head of gemini.google.com the way Gemini wraps its flags, as the Android tests build it. */
    private fun page(flags: String, region: String?) = buildString {
        append("<!doctype html><html lang=\"en\"><head><title>Gemini</title>")
        append("<script nonce=\"x\">window.WIZ_global_data = {\"Im6cmf\":\"/_/BardChatUi\",")
        append(flags)
        append("};</script>")
        if (region != null) append("<script>var og=[null,[1,2,3],2,1,200,\"$region\",null,\"en\"];</script>")
        append("</head><body><c-wiz></c-wiz></body></html>")
    }

    fun gemini(): String {
        val home = "https://gemini.google.com/"
        data class Case(val name: String, val code: Int, val url: String, val body: String, val status: GeminiStatus, val reason: GeminiReason)
        val cases = listOf(
            Case("switch on (object)", 200, home, page("\"45631641\":true", "USA"), GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON),
            Case("switch on (array)", 200, home, page("\"x\":[45631641,null,true]", "DEU"), GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON),
            Case("country first: Russia", 200, home, page("\"1\":2", "RUS"), GeminiStatus.UNSUPPORTED_REGION, GeminiReason.UNSUPPORTED_REGION_LIST),
            // The German exit of November 2026: switch off, the country one Gemini is offered in.
            Case("switch off in a supported country", 200, home, page("\"45631641\":false", "DEU"), GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.UNSURE),
            Case("switch off, no country", 200, home, page("\"45631641\":false", null), GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.UNSURE),
            // Another feature's sentence is no verdict on Gemini itself.
            Case(
                "another feature's \"not available\"", 200, home,
                page("\"z\":\"This feature is not available in your country\"", "FRA"),
                GeminiStatus.AVAILABLE, GeminiReason.SUPPORTED_REGION
            ),
            Case(
                "Gemini's own words", 200, home, page("\"z\":\"Gemini isn't available in your country\"", null),
                GeminiStatus.UNSUPPORTED_REGION, GeminiReason.NOT_AVAILABLE_TEXT
            ),
            Case("cookie wall", 200, "https://consent.google.com/m?continue=x", "<form action=\"https://consent.google.com/save\">", GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CONSENT),
            Case("captcha", 429, "https://www.google.com/sorry/index", "unusual traffic", GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CAPTCHA),
            Case("451", 451, home, "", GeminiStatus.UNSUPPORTED_REGION, GeminiReason.LEGAL_BLOCK)
        )
        val wrong = cases.mapNotNull { c ->
            val r = GeminiPage.evaluate(c.code, c.url, c.body)
            if (r.status == c.status && r.reason == c.reason) null else "${c.name}: ${r.status}/${r.reason}"
        }
        require(wrong.isEmpty()) { wrong.joinToString("; ") }
        require(GeminiPage.alpha2("USA") == "US" && GeminiPage.alpha2("NLD") == "NL") { "alpha-3 codes" }
        return "${cases.size} pages judged as on Android"
    }

    fun traffic(): String {
        val today = LocalDate.of(2026, 11, 8)
        val days = listOf(
            TrafficDayEntity(today.toEpochDay(), uplinkBytes = 30, downlinkBytes = 270),
            TrafficDayEntity(today.minusDays(1).toEpochDay(), uplinkBytes = 0, downlinkBytes = 0),
            TrafficDayEntity(today.minusDays(13).toEpochDay(), uplinkBytes = 4, downlinkBytes = 6),
            TrafficDayEntity(today.minusDays(14).toEpochDay(), uplinkBytes = 1_000, downlinkBytes = 1_000)
        )
        val s = TrafficTally.summarize(days, today)
        require(s.recent.size == TrafficTally.RECENT_DAYS && s.recentDown.size == s.recent.size && s.recentUp.size == s.recent.size) {
            "${s.recent.size}/${s.recentDown.size}/${s.recentUp.size}"
        }
        require(s.firstDay == today.minusDays(13).toEpochDay()) { "first bar ${LocalDate.ofEpochDay(s.firstDay)}" }
        require(s.recentDown.last() == 270L && s.recentUp.last() == 30L && s.recent.last() == 300L) { "today ${s.recent.last()}" }
        require(s.recentDown.first() == 6L && s.recentUp.first() == 4L) { "first ${s.recentDown.first()}/${s.recentUp.first()}" }
        require(s.recent.indices.all { s.recent[it] == s.recentDown[it] + s.recentUp[it] }) { "in + out ≠ total" }
        return "14 bars from ${LocalDate.ofEpochDay(s.firstDay)}, today ↓${s.recentDown.last()} ↑${s.recentUp.last()}"
    }

    fun raceJudge(): String {
        // The remembered node (rank 0) answers 120 ms after a faster stranger: within the
        // grace, it still wins; one that misses the grace loses.
        val judge = RaceJudge(size = 3, graceMs = 250)
        judge.record(rank = 2, ok = true, latencyMs = 40, at = 40)
        require(!judge.decided(now = 100)) { "decided before the better-ranked could answer" }
        judge.record(rank = 0, ok = true, latencyMs = 160, at = 160)
        require(judge.decided(now = 160) && judge.leader() == 0) { "leader ${judge.leader()}" }
        val late = RaceJudge(size = 2, graceMs = 250)
        late.record(rank = 1, ok = true, latencyMs = 30, at = 30)
        require(late.decided(now = 281) && late.leader() == 1) { "late: ${late.leader()}" }
        val nobody = RaceJudge(size = 2, graceMs = 250)
        nobody.record(0, false, -1, 10)
        nobody.record(1, false, -1, 20)
        require(nobody.decided(20) && nobody.leader() == null) { "nobody answered, yet ${nobody.leader()}" }
        // Who races: the remembered node first, then the best recent ones.
        val now = 100 * 60 * 60_000L
        fun node(id: Long, ms: Long, ago: Long) = ServerEntity(
            id = id, remark = "N$id", address = "n$id.example", port = 443,
            latencyMs = ms, ewmaMs = ms, lastPingAt = now - ago
        )
        val holder = node(4, 150, 60_000)
        val pool = listOf(node(1, 40, 60_000), node(2, 90, 60_000), node(3, 20, AutoBalancer.RECENT_MS + 1), holder)
        val order = AutoBalancer.raceContenders(pool, holder, now).map { it.id }
        require(order == listOf(4L, 1L, 2L, 3L)) { "contenders $order" }
        return "grace, late answer, silence; contenders $order"
    }
}
