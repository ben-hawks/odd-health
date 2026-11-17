package dev.oddhealth.app.update

import org.json.JSONObject

/** A published release that can be installed. */
data class Release(
    val version: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String?,
)

object Releases {
    /** Release asset names, as produced by the release workflow. */
    fun apkName(version: String) = "odd-health-$version.apk"

    /**
     * Parse GitHub's "latest release" JSON. Returns null when it has no APK for
     * its version (e.g. the build is still running).
     */
    fun parse(json: String): Release? {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.getString("tag_name")
        val version = tag.removePrefix("v")
        Version.parse(version) ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        var apk: JSONObject? = null
        var sha: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.getString("name")) {
                apkName(version) -> apk = a
                apkName(version) + ".sha256" -> sha = a
            }
        }
        apk ?: return null
        return Release(
            version = version,
            tag = tag,
            notes = o.optString("body"),
            pageUrl = o.optString("html_url"),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size"),
            sha256Url = sha?.getString("browser_download_url"),
        )
    }
}

/** MAJOR.MINOR.PATCH, compared numerically. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    companion object {
        private val pattern = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        fun parse(text: String): Version? =
            pattern.matchEntire(text.trim())?.destructured?.let { (a, b, c) -> Version(a.toInt(), b.toInt(), c.toInt()) }
    }
}
