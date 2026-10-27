package com.ars3nb.volta.core.routing

import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Happ-style routing geodata, kept separately for every subscription.
 *
 * A Happ routing profile may name its own `Geoipurl` / `Geositeurl`. Those files are
 * downloaded next to the core's assets as `geoip_s<ID>.dat` / `geosite_s<ID>.dat`, and the
 * subscription's rules are rewritten from `geoip:ru` to `ext:geoip_s<ID>.dat:ru`. Xray
 * resolves `ext:` names inside its asset directory, so two subscriptions can ship
 * different lists under the same tag without ever seeing each other's files, and the
 * bundled geoip.dat / geosite.dat stay untouched for everything else.
 *
 * Refresh contract (the same as Happ): files are fetched again when the provider bumps
 * `LastUpdated`, when a URL changes, when a file is missing, or after [MAX_AGE_MS].
 */
class GeoDataManager(private val assetDir: File) {

    data class Files(val geoip: File, val geosite: File) {
        val geoipName: String get() = geoip.name
        val geositeName: String get() = geosite.name
    }

    fun filesFor(subscriptionId: Long) = Files(
        geoip = File(assetDir, "geoip_s$subscriptionId.dat"),
        geosite = File(assetDir, "geosite_s$subscriptionId.dat")
    )

    /** Names of the files that are actually present for this subscription, or null. */
    fun installed(subscriptionId: Long): GeoNames? {
        val files = filesFor(subscriptionId)
        val ip = files.geoip.takeIf { it.isFile && it.length() > 0 }?.name
        val site = files.geosite.takeIf { it.isFile && it.length() > 0 }?.name
        return if (ip == null && site == null) null else GeoNames(ip, site)
    }

    /** True when [profile]'s geodata should be downloaded (again). */
    fun isStale(subscriptionId: Long, profile: RoutingProfileEntity, previous: RoutingProfileEntity?): Boolean {
        if (profile.geoipUrl.isBlank() && profile.geositeUrl.isBlank()) return false
        val files = filesFor(subscriptionId)
        if (profile.geoipUrl.isNotBlank() && !files.geoip.isFile) return true
        if (profile.geositeUrl.isNotBlank() && !files.geosite.isFile) return true
        if (previous == null) return true
        if (previous.geoipUrl != profile.geoipUrl || previous.geositeUrl != profile.geositeUrl) return true
        if (previous.geoVersion != profile.geoVersion) return true
        return System.currentTimeMillis() - previous.geoUpdatedAt > MAX_AGE_MS
    }

    /**
     * Downloads whatever [profile] names. Each file lands in a temp file first and only
     * replaces the old copy once it is complete, so a broken download never leaves the
     * core with a truncated .dat. Returns true when every named file is in place.
     */
    fun download(subscriptionId: Long, profile: RoutingProfileEntity, client: OkHttpClient = http): Boolean {
        val files = filesFor(subscriptionId)
        var ok = true
        if (profile.geoipUrl.isNotBlank()) ok = fetch(client, profile.geoipUrl, files.geoip) && ok
        if (profile.geositeUrl.isNotBlank()) ok = fetch(client, profile.geositeUrl, files.geosite) && ok
        return ok
    }

    fun delete(subscriptionId: Long) {
        val files = filesFor(subscriptionId)
        files.geoip.delete()
        files.geosite.delete()
    }

    private fun fetch(client: OkHttpClient, url: String, target: File): Boolean {
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) return false
        val temporary = File(target.parentFile, target.name + ".part")
        return try {
            val request = Request.Builder().url(url).header("User-Agent", "Volta/1.0 (Android)").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                temporary.outputStream().buffered().use { out -> body.byteStream().copyTo(out) }
            }
            // A real geo list is never this small; an HTML error page often is.
            if (temporary.length() < MIN_SIZE_BYTES) {
                temporary.delete()
                return false
            }
            target.delete()
            temporary.renameTo(target)
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    companion object {
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val MIN_SIZE_BYTES = 512L

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}

/** File names a config may reference through `ext:`; null = use the bundled lists. */
data class GeoNames(val geoip: String?, val geosite: String?) {

    /** `geoip:ru` → `ext:geoip_s7.dat:ru`; everything else is left alone. */
    fun rewrite(entry: String): String {
        val trimmed = entry.trim()
        return when {
            // `private` is a built-in range set that provider files often leave out.
            trimmed.equals("geoip:private", ignoreCase = true) -> entry

            geoip != null && trimmed.startsWith("geoip:", ignoreCase = true) ->
                "ext:$geoip:${trimmed.substringAfter(':')}"

            geosite != null && trimmed.startsWith("geosite:", ignoreCase = true) ->
                "ext:$geosite:${trimmed.substringAfter(':')}"

            else -> entry
        }
    }

    fun rewrite(rule: RoutingRuleDto): RoutingRuleDto = rule.copy(
        domains = rule.domains.map(::rewrite),
        ips = rule.ips.map(::rewrite)
    )
}
