package com.ars3nb.volta.core.share

import com.ars3nb.volta.core.link.ImportDetector
import com.ars3nb.volta.core.link.VoltaLinks
import java.net.URLEncoder

/**
 * What goes inside the encrypted message: plain UTF-8 text that the receiver imports
 * exactly as if it had been pasted into «Добавить». A subscription travels as a
 * `volta://import?url=…` link so its name, server-type markup and — above all — its
 * hidden flag survive: a hidden subscription is sent with `visible_subs=false`, the
 * receiver stores it hidden and never shows its address.
 */
object SharePayload {

    fun subscription(url: String, name: String, hidden: Boolean, serverTypes: String = ""): String {
        val params = buildList {
            add("url=" + encode(url.trim()))
            if (name.isNotBlank()) add("name=" + encode(name.trim()))
            if (hidden) add("visible_subs=false")
            if (serverTypes.isNotBlank()) add("server-type=" + encode(serverTypes))
        }
        return "volta://import?" + params.joinToString("&")
    }

    /**
     * What the «Добавить» field holds, to send instead of adding it here. A plain
     * subscription address given a [name] goes as an import link, so the name arrives
     * too; everything else (volta:// links included, hidden flag and all) goes as typed.
     */
    fun fromField(input: String, name: String): String {
        val t = input.trim()
        val plainAddress = (t.startsWith("https://", true) || t.startsWith("http://", true)) &&
            t.none { it.isWhitespace() }
        return if (plainAddress && name.isNotBlank()) subscription(t, name, hidden = false) else t
    }

    /** A server as its share link (or its JSON when it came as JSON); null when it has neither. */
    fun server(rawLink: String, rawJson: String): String? =
        rawLink.ifBlank { rawJson }.trim().takeIf { it.isNotEmpty() }

    /** What a received text is, said to the receiver before it is added. */
    data class Preview(
        val kind: ImportDetector.Kind,
        /** The name the link carries, if any. */
        val name: String,
        /** The address must never be shown (hidden or encrypted subscription). */
        val hidden: Boolean,
        /** A line of the text to recognise it by; null when [hidden]. */
        val excerpt: String?
    )

    fun preview(text: String): Preview {
        val t = text.trim()
        val kind = ImportDetector.detect(t)
        if (t.startsWith("volta://", ignoreCase = true)) {
            when (val a = VoltaLinks.parse(t)) {
                is VoltaLinks.AddSubscription ->
                    return Preview(kind, a.name, a.hidden, if (a.hidden) null else excerpt(a.url))
                is VoltaLinks.AddEncrypted -> return Preview(kind, a.name, true, null)
                else -> Unit
            }
        }
        return Preview(kind, "", false, excerpt(t))
    }

    private fun excerpt(text: String): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return if (line.length <= EXCERPT) line else line.take(EXCERPT - 1) + "…"
    }

    /** Percent-encoding that [VoltaLinks.decode] reads back: spaces as %20, never '+'. */
    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private const val EXCERPT = 120
}
