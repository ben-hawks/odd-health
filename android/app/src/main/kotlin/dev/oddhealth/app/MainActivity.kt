package dev.oddhealth.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.oddhealth.app.ui.AppShell
import dev.oddhealth.app.ui.AppActions
import dev.oddhealth.app.ui.model.Metric
import dev.oddhealth.app.ui.sample.SampleData
import dev.oddhealth.app.ui.theme.OddHealthTheme
import java.time.Instant

/** UI preview build: shows sample data until the data layer is wired in. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OddHealthTheme {
                AppShell(
                    today = SampleData.today,
                    ring = SampleData.ringScreen,
                    sync = SampleData.syncScreen,
                    detail = { if (it == Metric.SLEEP) SampleData.sleepDetail else SampleData.heartRateDetail },
                    now = Instant.now(),
                    actions = AppActions(),
                )
            }
        }
    }
}
