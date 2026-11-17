package dev.oddhealth.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.oddhealth.r1.R1
import dev.oddhealth.r1.Reassembler
import dev.oddhealth.r1.decode
import dev.oddhealth.r1.healthRequest
import dev.oddhealth.r1.packetAck
import dev.oddhealth.r1.parseInner
import dev.oddhealth.r1.systemRequest
import dev.oddhealth.r1.toHex
import java.util.UUID

/**
 * Keeps a GATT connection to the bonded ring and records everything it sends.
 *
 * The connection is shared with the Even app: both receive every notification,
 * so just by staying subscribed this service sees the Even app's syncs. It only
 * writes to the ring when asked to ([ACTION_REQUEST]) or, if enabled, to
 * acknowledge health data.
 *
 * It never turns notifications off: that setting is shared with the Even app.
 * It also never writes to the bae80011 subscription. Doing so on the phone's
 * link was followed by the link dropping about six seconds later, every time
 * (bae80010/11 is the channel the glasses use).
 */
@SuppressLint("MissingPermission") // MainActivity starts this only after BLUETOOTH_CONNECT is granted.
class RingService : Service() {

    companion object {
        const val ACTION_REQUEST = "dev.oddhealth.app.REQUEST"
        const val ACTION_STOP = "dev.oddhealth.app.STOP"
        const val EXTRA_ACKNOWLEDGE = "acknowledge"

        var running = false
            private set

        private const val CHANNEL = "ring"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 247
        private const val WRITE_GAP_MS = 250L
        private const val OP_TIMEOUT_MS = 3000L
        private const val SETUP_FALLBACK_MS = 4000L
        private const val OTHER_REPORT_MS = 2000L
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE = UUID.fromString(R1.SERVICE_UUID)
        private val WRITE = UUID.fromString(R1.WRITE_CHAR_UUID)
        private val NOTIFY = UUID.fromString(R1.NOTIFY_CHAR_UUID)
        private val STATUS_NOTIFY = UUID.fromString(R1.STATUS_NOTIFY_CHAR_UUID)
    }

    private val main = Handler(Looper.getMainLooper())
    private val reassembler = Reassembler()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var ready = false
    private var acknowledge = false
    private var frames = 0
    private var discovering = false
    private var dataSubscribed: Boolean? = null // what the ring reports for bae80013; null = unknown
    private var otherBatches = 0
    private var otherBytes = 0
    private val reportOther = Runnable {
        event("Other data from the ring (not health): $otherBatches transfers, $otherBytes bytes")
        otherBatches = 0
        otherBytes = 0
    }

    // Sequence numbers for our own frames, kept away from the Even app's low numbers.
    private var seq = 0x4F00

    // Android runs one GATT operation at a time; queue them.
    private val ops = ArrayDeque<Pair<String, (BluetoothGatt) -> Boolean>>()
    private var opRunning = false
    private val opTimeout = Runnable { opDone() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Ring connection", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        event("Odd Health ${packageManager.getPackageInfo(packageName, 0).versionName} started")
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_REQUEST -> requestData()
        }
        if (intent?.hasExtra(EXTRA_ACKNOWLEDGE) == true) {
            acknowledge = intent.getBooleanExtra(EXTRA_ACKNOWLEDGE, false)
            RingLog.event(this, if (acknowledge) "Acknowledging health data: on" else "Acknowledging health data: off")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        // close() drops only this app's use of the link; the Even app keeps its own.
        gatt?.close()
        gatt = null
        setStatus("Stopped")
        super.onDestroy()
    }

    // --- connection ----------------------------------------------------------

    private fun connect() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off")
            return
        }
        val ring: BluetoothDevice? = adapter.bondedDevices.firstOrNull { it.name?.startsWith("EVEN R1") == true }
        if (ring == null) {
            setStatus("No paired EVEN R1 ring found. Pair it in the Even app first.")
            return
        }
        setStatus("Waiting for ${ring.name}")
        gatt = ring.connectGatt(this, true, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, main)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            ready = false
            discovering = false
            dataSubscribed = null
            writeChar = null
            ops.clear()
            opRunning = false
            main.removeCallbacks(opTimeout)
            reassembler.reset()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                event("Connected")
                setStatus("Connected, setting up")
                if (!g.requestMtu(MTU)) discover(g)
                // If the MTU callback never comes, carry on without it.
                main.postDelayed({ if (gatt === g) discover(g) }, SETUP_FALLBACK_MS)
            } else {
                event("Disconnected (status $status)")
                setStatus("Waiting for ring")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            event("MTU $mtu")
            discover(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            if (service == null) {
                setStatus("Ring service not found")
                return
            }
            writeChar = service.getCharacteristic(WRITE)
            // Register locally for both channels. This costs nothing on the air.
            service.getCharacteristic(STATUS_NOTIFY)?.let { g.setCharacteristicNotification(it, true) }
            val data = service.getCharacteristic(NOTIFY)
            val cccd = data?.getDescriptor(CCCD)
            if (data == null || cccd == null) {
                setStatus("Ring data channel not found")
                return
            }
            g.setCharacteristicNotification(data, true)
            // Only write the subscription if the ring says it is off; the Even
            // app normally has it on already and the setting is shared.
            enqueue("check subscription") { it.readDescriptor(cccd) }
            enqueue("subscribe") {
                if (dataSubscribed == true) {
                    event("Ring notifications already on")
                    false
                } else {
                    event("Turning ring notifications on")
                    writeDescriptor(it, cccd)
                }
            }
            enqueue("ready") {
                ready = true
                event("Listening")
                setStatus("Listening")
                false // nothing was sent; move on immediately
            }
        }

        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) {
            if (status == BluetoothGatt.GATT_SUCCESS) dataSubscribed = value.isNotEmpty() && value[0].toInt() and 1 != 0
            opDone()
        }

        @Deprecated("Called instead of the four-argument version before Android 13")
        @Suppress("DEPRECATION")
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = d.value
                if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                    dataSubscribed = value.isNotEmpty() && value[0].toInt() and 1 != 0
                }
                opDone()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = opDone()

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = opDone()

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            received(c.uuid, value)

        @Deprecated("Called instead of the three-argument version before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) received(c.uuid, c.value ?: return)
        }
    }

    /** Service discovery, once per connection (the MTU callback can arrive more than once). */
    private fun discover(g: BluetoothGatt) {
        if (discovering) return
        discovering = true
        g.discoverServices()
    }

    // --- incoming ------------------------------------------------------------

    private fun received(uuid: UUID, value: ByteArray) {
        RingLog.raw(this, "N", shortName(uuid), value.toHex())
        if (uuid != NOTIFY) return
        try {
            val batch = reassembler.feedLenient(value) ?: return
            val inner = batch.inner
            if (!batch.crcOk || inner.size < R1.HEADER_LEN || inner[0].toInt() != R1.MARKER) {
                // The Even app also pulls other things over this channel (ring logs, for one).
                otherBatches++
                otherBytes += inner.size
                main.removeCallbacks(reportOther)
                main.postDelayed(reportOther, OTHER_REPORT_MS)
                return
            }
            val frame = parseInner(inner)
            frames++
            val decoded = decode(frame)
            val detail = when {
                decoded != null -> describe(decoded)
                frame.data.isEmpty() -> ""
                else -> "${frame.data.size} bytes"
            }
            event("${frame.name} #${frame.seq}" + if (detail.isEmpty()) "" else " — $detail")
            if (acknowledge && frame.module == R1.MODULE_HEALTH && frame.status == R1.STATUS_PUSH && frame.innerCrcOk) {
                send("packetAck #${frame.seq}", packetAck(frame, nextSeq()))
            }
            setStatus("Listening · $frames frames")
        } catch (e: IllegalArgumentException) {
            reassembler.reset()
            event("Unreadable frame: ${e.message}")
        }
    }

    // --- outgoing ------------------------------------------------------------

    private fun nextSeq(): Int = seq.also { seq = if (seq >= 0x4FFF) 0x4F00 else seq + 1 }

    /** Open a session and ask for device info, battery and each health metric. Reads only. */
    private fun requestData() {
        if (!ready) {
            event("Not connected; nothing requested")
            return
        }
        send("pairAuth", R1.PAIR_AUTH_FRAME)
        send("deviceInfo", systemRequest(R1.SYS_DEVICE_INFO, nextSeq()))
        send("deviceStatus", systemRequest(R1.SYS_DEVICE_STATUS, nextSeq()))
        for (cmd in listOf(R1.HEALTH_HEART_RATE, R1.HEALTH_SPO2, R1.HEALTH_HRV, R1.HEALTH_ACTIVITY, R1.HEALTH_SLEEP)) {
            send("${R1.HEALTH_CMDS[cmd]} request", healthRequest(cmd, nextSeq()))
        }
    }

    private fun send(label: String, frame: ByteArray) {
        val ch = writeChar ?: return
        enqueue(label) { g ->
            RingLog.raw(this, "W", shortName(WRITE), frame.toHex())
            event("Sent $label")
            writeCharacteristic(g, ch, frame)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return g.writeCharacteristic(ch, value, type) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        }
        ch.writeType = type
        ch.value = value
        return g.writeCharacteristic(ch)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return g.writeDescriptor(d, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        }
        d.value = value
        return g.writeDescriptor(d)
    }

    // --- operation queue -----------------------------------------------------

    /** op returns true if it started a GATT operation whose callback will follow. */
    private fun enqueue(label: String, op: (BluetoothGatt) -> Boolean) {
        ops.addLast(label to op)
        if (!opRunning) runNext()
    }

    private fun runNext() {
        val g = gatt ?: return
        while (true) {
            val (label, op) = ops.removeFirstOrNull() ?: return
            val started = try {
                op(g)
            } catch (e: RuntimeException) {
                event("$label failed: ${e.message}")
                false
            }
            if (started) {
                opRunning = true
                main.postDelayed(opTimeout, OP_TIMEOUT_MS)
                return
            }
        }
    }

    private fun opDone() {
        main.removeCallbacks(opTimeout)
        if (!opRunning) return
        // Leave a gap so the ring can answer before the next write.
        main.postDelayed({
            opRunning = false
            runNext()
        }, WRITE_GAP_MS)
    }

    // --- status --------------------------------------------------------------

    private fun shortName(uuid: UUID): String = uuid.toString().substring(0, 8)

    private fun event(text: String) = RingLog.event(this, text)

    private fun setStatus(text: String) {
        RingLog.status(text)
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Odd Health")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
