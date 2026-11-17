package dev.oddhealth.app.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.oddhealth.app.graph

/** Reconnects to the ring after a restart, if the user left "Stay connected" on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && context.graph.settings.stayConnected.value) {
            RingService.start(context)
        }
    }
}
