package com.hcebox.driver.androidnative

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.reader.protocol.BluetoothContract
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared scan owner for UI and AIDL callers; BLE observations expire after 20 seconds. */
@SuppressLint("MissingPermission")
class ReaderDiscovery(private val context: Context) {
    val devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val handler = Handler(Looper.getMainLooper())
    private val owners = mutableMapOf<Any, (Throwable) -> Unit>()
    private val observed = mutableMapOf<String, Pair<DeviceInfo, Long>>()
    val tcp = TcpDiscovery(context, { devices.value = context.nativeController.devices() }, { context.nativeController.log(it) })
    private var scanner: BluetoothLeScanner? = null
    private var generation = 0L
    private var activeCallback: ScanCallback? = null
    private fun callback(token: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post {
            if (generation != token || scanner == null || context.nativeController.mode != "BLE") return@post
            val device = DeviceInfo("BLE:${result.device.address}", result.scanRecord?.deviceName ?: "Android BLE Reader", "BLE ${result.device.address}")
            observed[device.deviceId] = device to android.os.SystemClock.elapsedRealtime()
            devices.value = observed.values.map { it.first }
        } }
        override fun onScanFailed(errorCode: Int) { handler.post { if (generation == token) failed(IllegalStateException("BLE scan failed code=$errorCode")) } }
    }
    private val expiry = object : Runnable {
        override fun run() {
            if (scanner == null) return
            val now = android.os.SystemClock.elapsedRealtime()
            observed.entries.removeAll { now - it.value.second > 20000 }
            devices.value = observed.values.map { it.first }
            handler.postDelayed(this, 1000)
        }
    }
    fun start(owner: Any, failure: (Throwable) -> Unit) { handler.post {
        owners[owner] = failure
        if (scanner != null || tcp.isRunning) return@post
        try {
            if (missingPermissions(context).isNotEmpty()) throw SecurityException("Transport permissions required")
            if (context.nativeController.mode == "TCP") {
                devices.value = context.nativeController.devices(); tcp.start(::failed); return@post
            }
            if (context.nativeController.mode != "BLE") { devices.value = context.nativeController.devices(); return@post }
            if (missingPermissions(context).isNotEmpty()) throw SecurityException("Bluetooth permissions required")
            observed.clear(); devices.value = emptyList()
            val active = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: error("BLE scanner unavailable")
            scanner = active
            val callback = callback(++generation); activeCallback = callback
            active.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BluetoothContract.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            handler.postDelayed(expiry, 1000)
        } catch (error: Exception) { failed(error) }
    } }
    fun reset() { handler.post { failed(IllegalStateException("Discovery configuration changed")); observed.clear(); devices.value = emptyList() } }
    fun refresh() { handler.post { if (context.nativeController.mode != "BLE") devices.value = context.nativeController.devices() } }
    fun stop(owner: Any) { handler.post { owners.remove(owner); if (owners.isEmpty()) stopScan() } }
    private fun stopScan() { tcp.stop(); generation++; activeCallback?.let { callback -> runCatching { scanner?.stopScan(callback) } }; activeCallback = null; scanner = null; handler.removeCallbacks(expiry) }
    private fun failed(error: Throwable) { val listeners = owners.values.toList(); owners.clear(); stopScan(); listeners.forEach { it(error) } }
}
