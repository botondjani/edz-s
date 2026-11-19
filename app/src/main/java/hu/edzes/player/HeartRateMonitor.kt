package hu.edzes.player

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
 *
 * Két úton keres egyszerre: a telefonhoz már csatlakoztatott / párosított eszközöket egyenként megpróbálja (a Fitbit a
 * Google Health app kapcsolata miatt ilyenkor sokszor nem hirdeti magát), és közben pulzusszolgáltatást hirdető
 * eszközöket keres. Az első, amelyiken van pulzusszolgáltatás, nyer. Szakadás után újrakezdi.
 * Minden visszahívás a fő szálon érkezik.
 */
@SuppressLint("MissingPermission")
class HeartRateMonitor(
    private val ctx: Context,
    private val onStatus: (String) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    private val onHeartRate: (Int) -> Unit,
) {
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null      // a próbált vagy már adatot küldő kapcsolat
    private var streaming = false
    private var running = false
    private var scanning = false
    private val candidates = ArrayDeque<BluetoothDevice>()
    private val tried = mutableSetOf<String>()
    private val refreshed = mutableSetOf<String>()   // a szolgáltatás-gyorsítótár ürítése eszközönként egyszer
    private val seen = mutableSetOf<String>()

    private val probeTimeout = Runnable { if (!streaming) { log("időtúllépés"); closeGatt(); tryNext() } }
    private val retry = Runnable { restart() }

    private fun log(text: String) = main.post { onLog(text) }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!running || streaming) return@post
                val d = result.device
                if (seen.add(d.address)) log("hirdet (pulzus): ${describe(result)}")
                if (gatt?.device?.address == d.address) return@post
                // A hirdetett pulzusszolgáltatású eszköz elsőbbséget kap a próbálgatással szemben.
                candidates.removeAll { it.address == d.address }
                candidates.addFirst(d)
                closeGatt()
                tryNext()
            }
        }
    }

    /** Szűrés nélküli keresés: a naplóhoz, és a nevük alapján valószínű órák/karkötők kipróbálásához. */
    private val anyScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!running || streaming) return@post
                val d = result.device
                if (!seen.add(d.address)) return@post
                log("látható: ${describe(result)}")
                val hr = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE } == true
                if ((hr || looksLikeWearable(result.scanRecord?.deviceName ?: name(d))) && d.address !in tried) {
                    candidates.addLast(d)
                    tryNext()
                }
            }
        }
    }

    private fun describe(r: ScanResult) =
        "${r.scanRecord?.deviceName ?: name(r.device)} (${r.device.address}, ${r.rssi} dBm" +
            (r.scanRecord?.serviceUuids?.takeIf { it.isNotEmpty() }?.joinToString(prefix = ", ") { short(it.uuid) } ?: "") + ")"

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("${name(g.device)}: " + (if (newState == BluetoothProfile.STATE_CONNECTED) "kapcsolódva" else "szétkapcsolva") + " (állapotkód $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) { g.discoverServices(); return }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return
            main.post {
                if (g !== gatt) { g.close(); return@post }
                val wasStreaming = streaming
                closeGatt()
                if (!running) return@post
                if (wasStreaming) {
                    onStatus("kapcsolat megszakadt, újrakeresés…")
                    main.postDelayed({ restart() }, 3000)
                } else tryNext()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("${name(g.device)} szolgáltatásai: " + g.services.joinToString { short(it.uuid) } + " (állapotkód $status)")
            val c = g.getService(SERVICE)?.getCharacteristic(MEASUREMENT)
            if (c == null) {
                // A párosított eszközök szolgáltatáslistáját az Android gyorsítótárazza; ha a pulzusmegosztás később
                // indult, a lista elavult lehet. Egyszer ürítjük, és újra lekérdezzük.
                if (refreshed.add(g.device.address) && refresh(g)) {
                    log("${name(g.device)}: gyorsítótár ürítve, újralekérdezés")
                    main.postDelayed({ if (g === gatt) g.discoverServices() }, 600)
                    return
                }
                main.post { if (g === gatt) { closeGatt(); tryNext() } else g.close() }
                return
            }
            g.setCharacteristicNotification(c, true)
            c.getDescriptor(CLIENT_CONFIG)?.let { d ->
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }
            main.post {
                if (g !== gatt) return@post
                log("${name(g.device)}: pulzusszolgáltatás megvan, feliratkozás")
                streaming = true
                main.removeCallbacks(probeTimeout)
                stopScan()
                onStatus("kapcsolódva: ${name(g.device)}")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("${name(g.device)}: értesítés bekapcsolása " + if (status == BluetoothGatt.GATT_SUCCESS) "sikerült" else "sikertelen (kód $status)")
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = report(value)

        @Deprecated("Android 12-ig ez hívódik")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") report(c.value ?: return)
        }
    }

    private fun report(value: ByteArray) {
        parseHeartRate(value)?.takeIf { it > 0 }?.let { bpm -> main.post { if (running) onHeartRate(bpm) } }
    }

    private fun name(d: BluetoothDevice) = runCatching { d.name }.getOrNull() ?: d.address

    fun start() {
        when {
            !hasPermission(ctx) -> onStatus("nincs Bluetooth-engedély")
            adapter?.isEnabled != true -> onStatus("a Bluetooth ki van kapcsolva")
            else -> { running = true; restart() }
        }
    }

    private fun restart() {
        main.removeCallbacks(retry)
        if (!running || streaming) return
        onStatus("pulzusmérő keresése…")
        tried.clear()
        candidates.clear()
        // A telefonhoz már kapcsolódó és a párosított BLE eszközök (fejhallgatót, autót és hasonlókat kihagyva).
        val connected = runCatching { manager!!.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList())
        val bonded = runCatching { adapter!!.bondedDevices.toList() }.getOrDefault(emptyList())
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
        log("kapcsolódó eszközök: " + connected.joinToString { "${name(it)} (${it.address})" }.ifEmpty { "nincs" })
        log("párosított BLE eszközök: " + bonded.joinToString { "${name(it)} (${it.address})" }.ifEmpty { "nincs" })
        // Az óra/karkötő gyanús nevűek előre, a többi utánuk.
        (connected + bonded).distinctBy { it.address }.sortedBy { if (looksLikeWearable(name(it))) 0 else 1 }.forEach { candidates.addLast(it) }
        startScan()
        tryNext()
    }

    private fun tryNext() {
        if (!running || streaming || gatt != null) return
        val d = generateSequence { candidates.removeFirstOrNull() }.firstOrNull { tried.add(it.address) }
        if (d == null) {
            // Elfogytak a jelöltek: 20 mp múlva újra (pl. ha a megosztást közben indították el a Google Health appban).
            onStatus("nem található pulzusmérő, újrapróbálás…")
            main.removeCallbacks(retry)
            main.postDelayed(retry, 20_000)
            return
        }
        onStatus("próbálkozás: ${name(d)}")
        log("próbálkozás: ${name(d)} (${d.address})")
        gatt = d.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        main.postDelayed(probeTimeout, 12_000)
    }

    private fun closeGatt() {
        main.removeCallbacks(probeTimeout)
        gatt?.let { runCatching { it.disconnect() }; it.close() }
        gatt = null
        streaming = false
    }

    private fun startScan() {
        if (scanning) return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        // A szűrt keresés kikapcsolt képernyőnél is fut; a szűretlen csak bekapcsolt képernyőnél (Android-korlát).
        scanning = runCatching {
            adapter!!.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()), settings, scanCallback)
            adapter.bluetoothLeScanner.startScan(null, settings, anyScanCallback)
        }.onFailure { log("keresési hiba: ${it.message}") }.isSuccess
    }

    private fun stopScan() {
        if (scanning) runCatching {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            adapter?.bluetoothLeScanner?.stopScan(anyScanCallback)
        }
        scanning = false
    }

    private fun refresh(g: BluetoothGatt) = runCatching { g.javaClass.getMethod("refresh").invoke(g) as Boolean }.getOrDefault(false)

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
    }

    companion object {
        private val SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** A futásidőben kérendő engedélyek (Android 12-től a „Közeli eszközök”, előtte a helymeghatározás). */
        val PERMISSIONS = if (Build.VERSION.SDK_INT >= 31)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

        /** 16 bites rövid alak a szabványos UUID-okra (pl. 180d), különben a teljes UUID. */
        fun short(u: UUID): String = u.toString().let { if (it.endsWith("-0000-1000-8000-00805f9b34fb") && it.startsWith("0000")) it.substring(4, 8) else it }

        fun looksLikeWearable(name: String?) =
            name != null && listOf("fitbit", "air", "charge", "edge", "pixel watch", "hrm", "polar", "garmin", "wahoo").any { it in name.lowercase() }

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
