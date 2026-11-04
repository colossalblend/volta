package com.ars3nb.volta.core.link

import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.server.ServerMarkup
import com.ars3nb.volta.data.model.ConnectMode
import com.google.gson.JsonParser

/**
 * Every `volta://` deep link, parsed without android.net.Uri (it mangles subscription
 * URLs that carry their own query string, and keeps this testable on the JVM).
 *
 * Adding a subscription
 * ```
 * volta://import/https://sub.example/abc               plain, link visible
 * volta://import/https://sub.example/abc?visible_subs=false
 * volta://import/https://sub.example/abc&server-type=lte:1,2;home:3#My VPN
 * volta://import?url=<encoded>&name=My%20VPN&visible_subs=false&server-type=lte:1-3
 * volta://crypt/<payload>                             encrypted (SubCrypto), link hidden
 * ```
 * `add/`, `sub/` and `subscribe/` work like `import/`. In the path form everything after
 * `import/` is the subscription address; only Volta's own parameters (`visible_subs`,
 * `server-type`) are taken out of its query, and a `#fragment` is the name.
 *
 * Switching the tunnel
 * ```
 * volta://connect            volta://connect?mode=home|lte|auto|manual
 * volta://disconnect         volta://toggle
 * ```
 * Anything else (`vless://…`, `volta://?config=…`, `volta://of/…`) is content to import.
 */
object VoltaLinks {

    sealed interface Action

    data class AddSubscription(
        val url: String,
        val name: String = "",
        /** `visible_subs` as given: null = not stated. */
        val visible: Boolean? = null,
        /** Canonical server-type markup (ServerMarkup.normalize), may be empty. */
        val serverTypes: String = ""
    ) : Action {
        val hidden: Boolean get() = visible == false
    }

    /** `volta://crypt/…`: open with [openEncrypted]. Outer name/server-type are hints. */
    data class AddEncrypted(
        val payload: String,
        val name: String = "",
        val serverTypes: String = ""
    ) : Action

    /** A share link, JSON, .conf or OpenFlux link for the ordinary importer. */
    data class ImportContent(val value: String) : Action

    data class Connect(val mode: ConnectMode? = null) : Action
    data object Disconnect : Action
    data object Toggle : Action
    /** A link Volta can't use; [message] says why, as a string resource. */
    data class Invalid(@param:StringRes val message: Int) : Action

    private val IMPORT_HOSTS = setOf("import", "add", "sub", "subscribe", "subscription", "install-config")
    private val CRYPT_HOSTS = setOf("crypt", "crypt1", "encrypted")
    private val CONNECT_HOSTS = setOf("connect", "on", "start", "enable", "up")
    private val DISCONNECT_HOSTS = setOf("disconnect", "off", "stop", "disable", "down")
    private val TOGGLE_HOSTS = setOf("toggle", "switch")

    private val VISIBLE_KEYS = setOf("visible_subs", "visible-subs", "visiblesubs", "visible")
    private val TYPE_KEYS = setOf("server-type", "server_type", "servertype", "server-types", "server_types")
    private val OWN_PATH_KEYS = VISIBLE_KEYS + TYPE_KEYS
    private val URL_KEYS = listOf("url", "link", "sub", "config", "data")

    fun parse(raw: String): Action {
        val t = raw.trim()
        OpenFluxLink.unwrap(t)?.let { return ImportContent(it) }
        if (!t.startsWith(SCHEME, ignoreCase = true)) return ImportContent(t)

        val rest = t.substring(SCHEME.length)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd).lowercase()
        val after = rest.substring(hostEnd)

        return when (host) {
            in IMPORT_HOSTS -> if (after.startsWith("/") && after.length > 1) {
                pathImport(after.substring(1))
            } else {
                queryImport(query(after))
            }

            in CRYPT_HOSTS -> crypt(after)
            in CONNECT_HOSTS -> Connect(parseMode(query(after).value("mode", "group", "type")))
            in DISCONNECT_HOSTS -> Disconnect
            in TOGGLE_HOSTS -> Toggle
            else -> queryImport(query(after))
        }
    }

    /**
     * Turns an opened `volta://crypt` payload into a subscription. The plaintext may be
     * the bare address, a `volta://import…` link (so a provider can also encrypt its
     * server-type), or `{"url": …, "name": …, "visible_subs": …, "server-type": …}`.
     * The link stays hidden unless the encrypted part itself says `visible_subs=true`:
     * the outer link is in plain sight, and anyone could add `visible_subs=true` to it.
     */
    fun openEncrypted(link: AddEncrypted, decrypt: (String) -> String = SubCrypto::decrypt): Action {
        val plain = try {
            decrypt(link.payload).trim()
        } catch (e: SubCrypto.CryptException) {
            return Invalid(e.reason)
        } catch (e: Exception) {
            return Invalid(R.string.settings_link_decrypt_failed)
        }
        val inner: AddSubscription = when {
            plain.startsWith(SCHEME, ignoreCase = true) -> when (val a = parse(plain)) {
                is AddSubscription -> a
                is AddEncrypted -> return Invalid(R.string.settings_link_double_encrypted)
                else -> return Invalid(R.string.settings_link_no_subscription)
            }

            plain.startsWith("{") -> runCatching {
                val o = JsonParser.parseString(plain).asJsonObject
                fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
                    o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
                }.orEmpty()
                AddSubscription(
                    url = s("url", "link", "sub"),
                    name = s("name", "title"),
                    visible = s(*VISIBLE_KEYS.toTypedArray()).takeIf { it.isNotBlank() }?.let(::parseBool),
                    serverTypes = ServerMarkup.normalize(s(*TYPE_KEYS.toTypedArray()))
                )
            }.getOrElse { return Invalid(R.string.settings_link_no_subscription) }

            else -> AddSubscription(url = withScheme(plain))
        }
        if (!isHttp(inner.url)) return Invalid(R.string.settings_link_no_address_encrypted)
        return inner.copy(
            name = inner.name.ifBlank { link.name },
            visible = inner.visible == true,
            serverTypes = inner.serverTypes.ifBlank { link.serverTypes }
        )
    }

    // ------------------------------------------------------------------ forms

    private fun pathImport(tailRaw: String): Action {
        var tail = tailRaw
        // The name rides in the fragment: a subscription address never needs one.
        var name = ""
        val hash = tail.lastIndexOf('#')
        if (hash >= 0) {
            name = decode(tail.substring(hash + 1)).trim()
            tail = tail.substring(0, hash)
        }
        if (tail.startsWith("http%3A", true) || tail.startsWith("https%3A", true)) tail = decode(tail)

        // Split the provider's query from ours. Volta's keys may follow '?' or, when the
        // address has no query of its own, a bare '&' ("…/sub&server-type=lte:1").
        var base = tail
        var queryPart = ""
        val q = tail.indexOf('?')
        if (q >= 0) {
            base = tail.substring(0, q)
            queryPart = tail.substring(q + 1)
        } else {
            val amp = OWN_PATH_KEYS.mapNotNull { k ->
                tail.indexOf("&$k=", ignoreCase = true).takeIf { it >= 0 }
            }.minOrNull()
            if (amp != null) {
                base = tail.substring(0, amp)
                queryPart = tail.substring(amp + 1)
            }
        }
        val own = mutableListOf<Pair<String, String>>()
        val theirs = mutableListOf<String>()
        queryPart.split('&').filter { it.isNotEmpty() }.forEach { p ->
            val key = p.substringBefore('=').lowercase()
            if (key in OWN_PATH_KEYS) own += key to decode(p.substringAfter('=', "")) else theirs += p
        }
        val url = withScheme(base + if (theirs.isEmpty()) "" else "?" + theirs.joinToString("&"))
        if (!isHttp(url)) return ImportContent(decode(tailRaw))
        return AddSubscription(
            url = url,
            name = name,
            visible = own.lastOrNull { it.first in VISIBLE_KEYS }?.second?.let(::parseBool),
            serverTypes = ServerMarkup.normalize(own.filter { it.first in TYPE_KEYS }.joinToString(";") { it.second })
        )
    }

    private fun queryImport(params: List<Pair<String, String>>): Action {
        val value = URL_KEYS.firstNotNullOfOrNull { k -> params.value(k).takeIf { it.isNotBlank() } }?.trim()
            ?: return Invalid(R.string.settings_link_no_address)
        if (!isHttp(value)) return ImportContent(value)
        return AddSubscription(
            url = value,
            name = params.value("name", "title").trim(),
            visible = params.value(*VISIBLE_KEYS.toTypedArray()).takeIf { it.isNotBlank() }?.let(::parseBool),
            serverTypes = ServerMarkup.normalize(
                params.filter { it.first in TYPE_KEYS }.joinToString(";") { it.second }
            )
        )
    }

    private fun crypt(after: String): Action {
        val payload: String
        val params: List<Pair<String, String>>
        if (after.startsWith("/") && after.length > 1) {
            val body = after.substring(1)
            payload = body.substringBefore('?').substringBefore('#')
            params = query(body.substring(payload.length))
        } else {
            params = query(after)
            payload = params.value("data", "payload", "d")
        }
        if (payload.isBlank()) return Invalid(R.string.settings_link_crypt_empty)
        return AddEncrypted(
            payload = payload.trim(),
            name = params.value("name", "title").trim(),
            serverTypes = ServerMarkup.normalize(params.filter { it.first in TYPE_KEYS }.joinToString(";") { it.second })
        )
    }

    // ---------------------------------------------------------------- helpers

    fun parseMode(raw: String?): ConnectMode? {
        val v = raw?.trim()?.lowercase()?.replace(" ", "")?.replace("-", "_") ?: return null
        if (v.isEmpty()) return null
        ConnectMode.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }?.let { return it }
        return when (v) {
            "home", "wifi", "h", "auto_home" -> ConnectMode.AUTO_HOME
            "lte", "l", "mobile", "auto_lte" -> ConnectMode.AUTO_LTE
            "auto", "a", "all", "home_lte", "homelte", "home+lte", "home_and_lte" -> ConnectMode.AUTO_HOME_LTE
            "manual", "own", "custom", "selected" -> ConnectMode.MANUAL
            else -> null
        }
    }

    private fun parseBool(v: String): Boolean = when (v.trim().lowercase()) {
        "false", "0", "no", "off", "hide", "hidden", "n" -> false
        else -> true
    }

    private fun isHttp(v: String) = v.startsWith("https://", true) || v.startsWith("http://", true)

    private fun withScheme(v: String): String {
        val s = v.trim()
        if (isHttp(s) || s.contains("://")) return s
        // "sub.example.com/abc": a host with a path, no scheme.
        return if (BARE_HOST_PATH.matches(s)) "https://$s" else s
    }

    private val BARE_HOST_PATH = Regex("^[\\w.-]+\\.[a-z]{2,}(?::\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)

    private fun query(after: String): List<Pair<String, String>> {
        val q = after.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyList()
        return q.split('&').filter { it.isNotEmpty() }.map { p ->
            p.substringBefore('=').lowercase() to decode(p.substringAfter('=', ""))
        }
    }

    private fun List<Pair<String, String>>.value(vararg keys: String): String =
        keys.firstNotNullOfOrNull { k -> lastOrNull { it.first == k }?.second }.orEmpty()

    /** %XX as UTF-8. '+' stays '+': subscription tokens use it, server-type copes with it. */
    fun decode(s: String): String {
        if (!s.contains('%')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 <= s.lastIndex) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private const val SCHEME = "volta://"
}
