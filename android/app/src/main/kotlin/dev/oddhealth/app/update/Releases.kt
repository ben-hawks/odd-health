package dev.oddhealth.app.update

import org.json.JSONArray
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

    /** What GitHub's release list says, for this build. */
    data class Check(
        /** The newest release that can be installed: has its APK and checksum. */
        val installable: Release?,
        /** A newer version that is published but still has no APK (its build is running). */
        val building: String?,
    )

    /**
     * Read GitHub's release list (newest first). The newest release isn't
     * always installable yet: its APK is uploaded several minutes after it is
     * published, so pick the newest one that has its files.
     */
    fun check(json: String): Check {
        val list = JSONArray(json)
        var installable: Release? = null
        var building: Version? = null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue
            val version = Version.parse(o.optString("tag_name")) ?: continue
            val release = parse(o)
            if (release == null) {
                if (building == null || version > building) building = version
            } else if (installable == null || version > Version.parse(installable.version)!!) {
                installable = release
            }
        }
        val installableVersion = installable?.let { Version.parse(it.version) }
        val stillBuilding = building?.takeIf { installableVersion == null || it > installableVersion }
        return Check(installable, stillBuilding?.let { "${it.major}.${it.minor}.${it.patch}" })
    }

    /** Parse one release object. Null when it is a draft or pre-release, or has no APK and checksum yet. */
    fun parse(json: String): Release? = parse(JSONObject(json))

    fun parse(o: JSONObject): Release? {
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
        // Both files are needed: the checksum is verified before installing.
        if (apk == null || sha == null) return null
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
