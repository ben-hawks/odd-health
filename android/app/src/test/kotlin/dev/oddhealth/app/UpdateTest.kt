package dev.oddhealth.app

import dev.oddhealth.app.update.Releases
import dev.oddhealth.app.update.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateTest {
    private fun release(tag: String, assets: String, prerelease: Boolean = false) = """
        {"tag_name": "$tag", "draft": false, "prerelease": $prerelease, "body": "Notes",
         "html_url": "https://github.com/o/r/releases/tag/$tag", "assets": [$assets]}
    """.trimIndent()

    private fun asset(name: String) = """{"name": "$name", "size": 2700000, "browser_download_url": "https://github.com/o/r/releases/download/x/$name"}"""

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Version.parse("0.15.0")!! > Version.parse("0.14.9")!!)
        assertTrue(Version.parse("v1.0.0")!! > Version.parse("0.99.99")!!)
        assertTrue(Version.parse("0.10.0")!! > Version.parse("0.9.0")!!)
        assertNull(Version.parse("0.14"))
    }

    @Test
    fun parsesTheReleaseWorkflowsAssets() {
        val r = Releases.parse(release("v0.15.0", asset("odd-health-0.15.0.apk") + "," + asset("odd-health-0.15.0.apk.sha256")))!!
        assertEquals("0.15.0", r.version)
        assertTrue(r.apkUrl.endsWith("odd-health-0.15.0.apk"))
        assertTrue(r.sha256Url!!.endsWith(".sha256"))
    }

    @Test
    fun picksTheNewestReleaseThatHasItsFilesWhileANewerOneIsBuilding() {
        // What GitHub returned at 15:08: v0.17.0 published, its build still running.
        val list = "[" + listOf(
            release("v0.17.0", ""),
            release("v0.16.0", asset("odd-health-0.16.0.apk") + "," + asset("odd-health-0.16.0.apk.sha256")),
            release("v0.15.0", asset("odd-health-0.15.0.apk") + "," + asset("odd-health-0.15.0.apk.sha256")),
        ).joinToString(",") + "]"
        val check = Releases.check(list)
        assertEquals("0.16.0", check.installable!!.version)
        assertEquals("0.17.0", check.building)
    }

    @Test
    fun nothingIsBuildingWhenTheNewestReleaseHasItsFiles() {
        val list = "[" + release("v0.16.0", asset("odd-health-0.16.0.apk") + "," + asset("odd-health-0.16.0.apk.sha256")) + "]"
        assertNull(Releases.check(list).building)
    }

    @Test
    fun ignoresReleasesWithoutAnApkOrMarkedPrerelease() {
        assertNull(Releases.parse(release("v0.15.0", asset("odd-health-0.15.0.apk"))))
        assertNull(Releases.parse(release("v0.15.0", asset("notes.txt"))))
        assertNull(Releases.parse(release("v0.15.0", asset("odd-health-0.15.0.apk"), prerelease = true)))
        assertNull(Releases.parse(release("nightly", asset("odd-health-nightly.apk"))))
    }
}
