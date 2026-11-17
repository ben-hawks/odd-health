package dev.oddhealth.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

/** One screen: connection status, a few controls, and the running event log. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var listenButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 18f }
        listenButton = Button(this).apply { setOnClickListener { toggleListening() } }
        val request = Button(this).apply {
            text = "Request data"
            setOnClickListener { command(RingService.ACTION_REQUEST) }
        }
        val share = Button(this).apply {
            text = "Share log"
            setOnClickListener { shareLog() }
        }
        val acknowledge = CheckBox(this).apply {
            text = "Acknowledge health data (marks it as synced on the ring)"
            setOnCheckedChangeListener { _, checked ->
                if (RingService.running) {
                    startService(Intent(this@MainActivity, RingService::class.java).putExtra(RingService.EXTRA_ACKNOWLEDGE, checked))
                }
            }
        }
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(log) }

        val buttons = LinearLayout(this).apply {
            addView(listenButton)
            addView(request)
            addView(share)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                fitsSystemWindows = true
                setPadding(pad, pad, pad, pad)
                addView(status)
                addView(buttons)
                addView(acknowledge)
                addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            },
        )

        if (missingPermissions().isEmpty()) startListening() else requestPermissions(missingPermissions(), 1)
    }

    override fun onResume() {
        super.onResume()
        RingLog.onChange = ::render
        render()
    }

    override fun onPause() {
        RingLog.onChange = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            RingLog.status("Bluetooth permission is needed to reach the ring")
        }
    }

    private fun missingPermissions(): Array<String> {
        val wanted = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun startListening() {
        startForegroundService(Intent(this, RingService::class.java))
        render()
    }

    private fun toggleListening() {
        if (RingService.running) {
            command(RingService.ACTION_STOP)
        } else if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermissions(missingPermissions(), 1)
        }
    }

    private fun command(action: String) {
        if (RingService.running) startService(Intent(this, RingService::class.java).setAction(action))
    }

    private fun shareLog() {
        val file = RingLog.latestCapture(this)
        if (file == null) {
            Toast.makeText(this, "Nothing captured yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "dev.oddhealth.app.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, file.name))
    }

    private fun render() {
        status.text = RingLog.status
        listenButton.text = if (RingService.running) "Stop" else "Listen"
        log.text = RingLog.lines.joinToString("\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
