package dev.oddhealth.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings as AndroidSettings
import dev.oddhealth.app.BuildConfig
import dev.oddhealth.app.Settings
import dev.oddhealth.app.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * Checks the project's GitHub releases, downloads the APK, verifies its
 * checksum and hands it to Android's installer. The network is used for
 * nothing else: no health data and no identifiers are sent.
 *
 * Android itself refuses an update signed with a different key, so only
 * official release builds can update each other.
 */
class UpdateManager(private val context: Context, private val settings: Settings, private val log: DebugLog) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val mutex = Mutex()

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** Debug builds are signed with the test key; a release can't install over them. */
    val canInstall: Boolean = !BuildConfig.DEBUG

    /** Ask GitHub for the latest release. Returns it when newer than this build. */
    suspend fun check(): Release? = mutex.withLock {
        _state.value = UpdateState.Checking
        try {
            val json = withContext(Dispatchers.IO) { get(latestUrl).decodeToString() }
            settings.setLastUpdateCheck(Instant.now())
            val release = Releases.parse(json)
            val current = Version.parse(currentVersion)
            val latest = release?.let { Version.parse(it.version) }
            if (release != null && latest != null && current != null && latest > current) {
                _state.value = UpdateState.Available(release)
                release
            } else {
                _state.value = UpdateState.UpToDate(release?.version ?: currentVersion)
                null
            }
        } catch (e: IOException) {
            log.event("Update check failed: $e")
            _state.value = UpdateState.Failed(e.message ?: e.toString())
            null
        } catch (e: org.json.JSONException) {
            _state.value = UpdateState.Failed("Unexpected reply from GitHub")
            null
        }
    }

    /** Whether Android lets this app install packages (the "install unknown apps" setting). */
    fun installAllowed() = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Download, verify and start installing [release]. Android then asks the user to confirm. */
    suspend fun downloadAndInstall(release: Release) = mutex.withLock {
        try {
            val apk = withContext(Dispatchers.IO) { download(release) }
            _state.value = UpdateState.Installing(release)
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: IOException) {
            log.event("Update download failed: $e")
            _state.value = UpdateState.Failed(e.message ?: e.toString(), release)
        } catch (e: SecurityException) {
            _state.value = UpdateState.Failed(e.message ?: e.toString(), release)
        }
    }

    /** Called by [InstallResultReceiver] when the installer finishes or fails. */
    fun installFinished(message: String?, release: Release?) {
        _state.value = if (message == null) UpdateState.Idle else UpdateState.Failed(message, release)
    }

    private val lastRelease get() = (state.value as? UpdateState.Installing)?.release

    fun installFailed(message: String) = installFinished(message, lastRelease)

    private fun download(release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, Releases.apkName(release.version))
        val expected = release.sha256Url?.let { get(it).decodeToString().trim().substringBefore(' ').lowercase() }
            ?: throw IOException("The release has no checksum file")
        val digest = MessageDigest.getInstance("SHA-256")
        open(release.apkUrl).let { conn ->
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw IOException("Downloaded file doesn't match its checksum")
        }
        return file
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("odd-health.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    private val latestUrl get() = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        // GitHub requires a User-Agent; nothing identifying beyond the app version.
        conn.setRequestProperty("User-Agent", "OddHealth-Updater/$currentVersion")
        conn.setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "application/octet-stream")
        if (conn.responseCode !in 200..299) {
            val code = conn.responseCode
            conn.disconnect()
            throw IOException(if (code == 404) "No published release found" else "GitHub returned HTTP $code")
        }
        return conn
    }

    private fun get(url: String): ByteArray = open(url).let { c -> c.inputStream.use { it.readBytes() }.also { c.disconnect() } }
}
