package com.hcebox.driver.androidnative.connection.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ReaderScanner
import com.hcebox.reader.protocol.contract.BluetoothContract
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service-UUID scan on the owner's queued Main scope; observations expire after 20 seconds and a
 * scan generation fences late callbacks.
 */
@SuppressLint("MissingPermission")
class BleScanner(
    private val context: Context,
    private val changed: (List<DeviceInfo>) -> Unit,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
) : ReaderScanner {
    private val observed = mutableMapOf<String, Pair<DeviceInfo, Long>>()
    private var expiryJob: Job? = null
    private var scanner: BluetoothLeScanner? = null
    private var generation = 0L
    private var activeCallback: ScanCallback? = null

    override val isRunning
        get() = scanner != null

    private fun callback(token: Long, failure: (Throwable) -> Unit) =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scope.launch {
                    if (generation != token || scanner == null) return@launch
                    val device =
                        DeviceInfo(
                            "BLE:${result.device.address}",
                            result.scanRecord?.deviceName ?: "Android BLE Reader",
                            "BLE ${result.device.address}",
                        )
                    observed[device.deviceId] = device to android.os.SystemClock.elapsedRealtime()
                    changed(observed.values.map { it.first })
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    if (generation == token)
                        failure(IllegalStateException("BLE scan failed code=$errorCode"))
                }
            }
        }

    private fun startExpiry(token: Long) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            log("BLE expiry monitor started")
            try {
                while (isActive) {
                    delay(1.seconds)
                    if (generation != token || scanner == null) return@launch
                    val now = android.os.SystemClock.elapsedRealtime()
                    observed.entries.removeAll { now - it.value.second > 20000 }
                    changed(observed.values.map { it.first })
                }
            } finally {
                log("BLE expiry monitor stopped")
            }
        }
    }

    override fun start(failure: (Throwable) -> Unit) {
        if (scanner != null) return
        observed.clear()
        changed(emptyList())
        val active =
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                ?: error("BLE scanner unavailable")
        scanner = active
        val token = ++generation
        val callback = callback(token, failure)
        activeCallback = callback
        active.startScan(
            listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(BluetoothContract.SERVICE)).build()
            ),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        startExpiry(token)
    }

    override fun stop() {
        generation++
        activeCallback?.let { callback -> runCatching { scanner?.stopScan(callback) } }
        activeCallback = null
        scanner = null
        expiryJob?.cancel()
        expiryJob = null
        observed.clear()
    }
}
