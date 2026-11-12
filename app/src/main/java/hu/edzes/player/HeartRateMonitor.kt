package hu.edzes.player

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Élő pulzus a szabványos Bluetooth LE Heart Rate szolgáltatásból (mellkaspánt, vagy pulzust megosztó óra/karkötő).
 * Megkeresi az első ilyen eszközt, csatlakozik, és szakadás után újrapróbálja. A visszahívás a fő szálon érkezik.
 */
@SuppressLint("MissingPermission")
class HeartRateMonitor(private val ctx: Context, private val onHeartRate: (Int) -> Unit) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var running = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            gatt = result.device.connectGatt(ctx, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                main.post { gatt = null; if (running) main.postDelayed(::scan, 5000) }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SERVICE)?.getCharacteristic(MEASUREMENT) ?: return g.disconnect()
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CLIENT_CONFIG) ?: return
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else @Suppress("DEPRECATION") { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = report(value)

        @Deprecated("Android 12-ig ez hívódik")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") report(c.value ?: return)
        }
    }

    private fun report(value: ByteArray) {
        parseHeartRate(value)?.let { bpm -> main.post { if (running) onHeartRate(bpm) } }
    }

    fun start() {
        if (adapter?.isEnabled != true || !hasPermission(ctx)) return
        running = true
        scan()
    }

    private fun scan() {
        if (!running || gatt != null) return
        runCatching {
            adapter?.bluetoothLeScanner?.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback,
            )
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        gatt?.close()
        gatt = null
    }

    companion object {
        private val SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** A futásidőben kérendő engedélyek (Android 12-től a „Közeli eszközök”, előtte a helymeghatározás). */
        val PERMISSIONS = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun hasPermission(ctx: Context) = PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

        /** Heart Rate Measurement: az 1. bájt jelzi, hogy a pulzus 8 vagy 16 bites. */
        fun parseHeartRate(v: ByteArray): Int? = when {
            v.size < 2 -> null
            v[0].toInt() and 1 == 0 -> v[1].toInt() and 0xFF
            v.size >= 3 -> (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
            else -> null
        }
    }
}
