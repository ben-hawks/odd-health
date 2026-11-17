package dev.oddhealth.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.oddhealth.app.ring.RingService
import dev.oddhealth.app.ui.AppActions
import dev.oddhealth.app.ui.AppShell
import dev.oddhealth.app.ui.AppViewModel
import dev.oddhealth.app.ui.Dest
import dev.oddhealth.app.ui.theme.OddHealthTheme
import dev.oddhealth.app.update.UpdateWorker
import dev.oddhealth.healthconnect.HealthConnectMapping

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Health Connect opens us to explain how data is used; show the About screen then.
        val start = if (intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" ||
            intent?.action == Intent.ACTION_VIEW_PERMISSION_USAGE
        ) Dest.About else if (intent?.getBooleanExtra(UpdateWorker.EXTRA_OPEN_UPDATES, false) == true) Dest.Updates else Dest.Today

        setContent {
            OddHealthTheme {
                val today by vm.today.collectAsStateWithLifecycle()
                val ring by vm.ring.collectAsStateWithLifecycle()
                val sync by vm.sync.collectAsStateWithLifecycle()
                val debug by vm.debug.collectAsStateWithLifecycle()
                val updates by vm.updates.collectAsStateWithLifecycle()
                val now by vm.now.collectAsStateWithLifecycle()
                val importing by vm.importing.collectAsStateWithLifecycle()
                val refreshing by vm.refreshing.collectAsStateWithLifecycle()
                val noticeAccepted by graph.settings.noticeAccepted.collectAsStateWithLifecycle()

                val btPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    if (graph.settings.stayConnected.value) RingService.start(this)
                }
                val hcPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
                    vm.refreshHealthConnect()
                    vm.syncNow()
                }
                val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.importExport(uri)
                }

                LaunchedEffect(Unit) {
                    val wanted = buildList {
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                    if (wanted.isNotEmpty()) btPermissions.launch(wanted.toTypedArray())
                    else if (graph.settings.stayConnected.value) RingService.start(this@MainActivity)
                }
                LaunchedEffect(Unit) {
                    vm.message.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
                }

                AppShell(
                    today = today, ring = ring, sync = sync, debug = debug, updates = updates,
                    detail = vm::detail, now = now, importing = importing, refreshing = refreshing, start = start,
                    version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
                    noticeAccepted = noticeAccepted,
                    actions = AppActions(
                        requestData = vm::requestData,
                        setListening = vm::setStayConnected,
                        refresh = { vm.refresh() },
                        setFrequentUpdates = vm::setFrequentUpdates,
                        battery = {
                            val unrestricted = vm.ring.value?.batteryUnrestricted == true
                            // Android's own prompt when optimised; the app's settings page otherwise (to change it back).
                            startActivity(
                                if (unrestricted) Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
                                else Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")),
                            )
                        },
                        syncNow = { vm.syncNow() },
                        // Launched directly from the tap, as Android 14+ requires.
                        permissions = { hcPermissions.launch(HealthConnectMapping.permissions) },
                        importExport = { pickExport.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        setDebugLogging = vm::setDebugLogging,
                        shareDebugLogs = { shareDebugLogs() },
                        deleteDebugLogs = vm::deleteDebugLogs,
                        acceptNotice = graph.settings::acceptNotice,
                        checkUpdate = { vm.checkForUpdate() },
                        installUpdate = {
                            // Android must first allow this app to install apps; send the user to that setting.
                            if (!vm.installUpdate()) startActivity(graph.updates.installPermissionIntent())
                        },
                        setUpdateNotifications = vm::setUpdateNotifications,
                        reportBug = { includeLogs ->
                            lifecycleScope.launch {
                                val url = vm.prepareBugReport(includeLogs)
                                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            }
                        },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshHealthConnect()
        vm.refreshBattery()
    }

    private fun shareDebugLogs() = lifecycleScope.launch {
        val zip = withContext(Dispatchers.IO) { graph.debugLog.zip() } ?: return@launch
        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share_logs)))
    }
}
