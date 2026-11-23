package dev.oddhealth.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.oddhealth.app.ui.AppActions
import dev.oddhealth.app.ui.detail.MetricDetailScreen
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.ring.RingScreen
import dev.oddhealth.app.ui.sample.SampleData
import dev.oddhealth.app.ui.sync.SyncScreen
import dev.oddhealth.app.ui.AppShell
import dev.oddhealth.app.ui.debug.DebugScreen
import dev.oddhealth.app.ui.model.DebugState
import kotlinx.coroutines.flow.flowOf
import dev.oddhealth.app.ui.theme.OddHealthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Renders each screen with sample data to PNGs under app/screenshots/.
 * Run: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-11-17T17:42:00Z")

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { OddHealthTheme(darkTheme = dark, dynamicColor = false, content = content) }
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Composable
    private fun shell() = AppShell(
        SampleData.today, SampleData.ringScreen, SampleData.syncScreen, DebugState(false, 0, 0),
        detail = { _, _ -> flowOf(SampleData.heartRateDetail) }, now = now, actions = AppActions(),
    )

    @Test fun today() = shot("1-today") { shell() }

    /** The whole Today page on one tall screen. */
    @Config(qualifiers = "w412dp-h1900dp-xxhdpi")
    @Test fun todayFull() = shot("1b-today-full") { shell() }

    @Test fun todayRefreshing() = shot("1c-today-refreshing") {
        AppShell(
            SampleData.today, SampleData.ringScreen, SampleData.syncScreen, DebugState(false, 0, 0),
            detail = { _, _ -> flowOf(SampleData.heartRateDetail) }, now = now, actions = AppActions(), refreshing = true,
        )
    }

    @Test fun todayDark() = shot("2-today-dark", dark = true) { shell() }

    @Test fun heartRate() = shot("3-heart-rate") { MetricDetailScreen(SampleData.heartRateDetail, {}, {}, null) }

    @Test fun sleep() = shot("4-sleep") { MetricDetailScreen(SampleData.sleepDetail, {}, {}, null) }

    @Test fun ring() = shot("5-ring") { RingScreen(SampleData.ringScreen, now, {}, {}, {}, {}) }

    @Test fun about() = shot("9-about") { dev.oddhealth.app.ui.about.AboutScreen("0.14.0", onBack = {}) }

    @Test fun firstRun() = shot("10-first-run") {
        AppShell(
            SampleData.today, SampleData.ringScreen, SampleData.syncScreen, DebugState(false, 0, 0),
            detail = { _, _ -> flowOf(SampleData.heartRateDetail) }, now = now, actions = AppActions(), noticeAccepted = false,
        )
    }

    @Test fun updates() = shot("11-updates") {
        dev.oddhealth.app.ui.update.UpdateScreen(
            dev.oddhealth.app.ui.update.UpdateScreenState(
                current = "0.14.0",
                state = dev.oddhealth.app.update.UpdateState.Available(
                    dev.oddhealth.app.update.Release("0.15.0", "v0.15.0", "- App updates from GitHub\n- Update notifications",
                        "", "", 2_700_000, null),
                ),
                notifications = true, lastCheck = now.minusSeconds(120), canInstall = true,
            ),
            now, {}, {}, {}, {},
        )
    }

    @Test fun report() = shot("12-report") {
        dev.oddhealth.app.ui.report.ReportScreen(DebugState(true, 2, 380_000), {}, {}, {})
    }

    @Test fun debug() = shot("8-debug") {
        DebugScreen(DebugState(true, 3, 412_000, connected = true, lastCheck = SampleData.checkResult), {}, {}, {}, {})
    }

    @Test fun checks() = shot("13-checks") {
        dev.oddhealth.app.ui.debug.CheckDialog(SampleData.checkResult, {}, {})
    }

    @Test fun sync() = shot("6-sync") { SyncScreen(SampleData.syncScreen, now, {}, {}, {}) }

    /** The whole Sync tab, down to Updates, Report a bug and About. */
    @Config(qualifiers = "w412dp-h1700dp-xxhdpi")
    @Test fun syncFull() = shot("6b-sync-full") { SyncScreen(SampleData.syncScreen, now, {}, {}, {}) }

    @Test fun syncDark() = shot("7-sync-dark", dark = true) { SyncScreen(SampleData.syncScreen, now, {}, {}, {}) }
}
