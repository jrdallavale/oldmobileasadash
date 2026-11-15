package com.oldmobile.biketracker

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Parses a 0x2A37 Heart Rate Measurement value. */
fun parseHr(v: ByteArray): Int {
    if (v.size < 2) return 0
    val u16 = (v[0].toInt() and 1) != 0
    return if (u16 && v.size >= 3) (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8) else v[1].toInt() and 0xff
}

/**
 * BLE central for the standard Heart Rate Service. Works with an Apple Watch broadcaster app
 * (HeartCast / HeartBLE) or any chest strap. Reconnects every 10 s when the link drops.
 */
@SuppressLint("MissingPermission")
class HeartRateClient(
    private val ctx: Context,
    private val handler: Handler,
    private val prefs: Prefs,
    private val onHr: (Int) -> Unit,
) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var scanning = false
    @Volatile var connected = false; private set
    @Volatile private var lastHrAt = 0L

    val hrFresh get() = connected && SystemClock.elapsedRealtime() - lastHrAt < 5000

    private val retry = Runnable { connect() }

    fun start() { running = true; connect() }

    fun stop() {
        running = false
        handler.removeCallbacks(retry)
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; connected = false
    }

    private fun scheduleRetry() {
        if (!running) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, 10_000)
    }

    private fun connect() {
        if (!running) return
        val a = adapter
        if (a == null || !a.isEnabled) { scheduleRetry(); return }
        val addr = prefs.hrAddress
        if (addr.isNotBlank()) {
            try { open(a.getRemoteDevice(addr)) } catch (_: Exception) { scheduleRetry() }
        } else scan(a)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { if (scanning) { stopScan(); open(result.device) } }
        }
        override fun onScanFailed(errorCode: Int) { handler.post { scanning = false; scheduleRetry() } }
    }

    private fun scan(a: BluetoothAdapter) {
        val sc = a.bluetoothLeScanner ?: run { scheduleRetry(); return }
        scanning = true
        try {
            sc.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        } catch (_: Exception) { scanning = false; scheduleRetry(); return }
        handler.postDelayed({ if (scanning) { stopScan(); scheduleRetry() } }, 8000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun open(dev: BluetoothDevice) {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        // if nothing happens in 15 s, give up and retry
        handler.postDelayed({ if (running && !connected) { try { gatt?.close() } catch (_: Exception) {}; gatt = null; scheduleRetry() } }, 15_000)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true
                    g.discoverServices()
                } else {
                    connected = false
                    onHr(0)
                    try { g.close() } catch (_: Exception) {}
                    if (gatt == g) gatt = null
                    scheduleRetry()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                if (ch == null) { g.disconnect(); return@post }
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
        }

        @Deprecated("old callback, still the one Android 8 uses")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            val v = ch.value ?: return
            val hr = parseHr(v)
            handler.post { lastHrAt = SystemClock.elapsedRealtime(); onHr(hr) }
        }
    }
}

/** Short scan used by Settings to pick a sensor. */
@SuppressLint("MissingPermission")
class HrScanner(ctx: Context, private val handler: Handler, private val onFound: (String, String) -> Unit, private val onEnd: () -> Unit) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val seen = HashSet<String>()
    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val a = r.device.address
            val name = r.scanRecord?.deviceName ?: r.device.name ?: a
            handler.post { if (seen.add(a)) onFound(name, a) }
        }
    }

    fun start(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        val sc = a.bluetoothLeScanner ?: return false
        return try {
            sc.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
            handler.postDelayed({ stop() }, 10_000)
            true
        } catch (_: Exception) { false }
    }

    fun stop() {
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
        handler.post(onEnd)
    }
}
