package com.hcebox.driver.androidnative.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.tcp.TcpDiscovery
import com.hcebox.reader.protocol.contract.BluetoothContract
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared scan owner for UI and AIDL callers; BLE observations expire after 20 seconds. The owner
 * supplies a queued Main scope and must stop scans before cancelling it.
 */
@SuppressLint("MissingPermission")
class ReaderDiscovery(
    private val context: Context,
    private val mode: () -> ConnectionMode,
    private val permissions: () -> Array<String>,
    private val listDevices: () -> List<DeviceInfo>,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
) {
    val devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private var expiryJob: Job? = null
    private val owners = mutableMapOf<Any, (Throwable) -> Unit>()
    private val observed = mutableMapOf<String, Pair<DeviceInfo, Long>>()
    val tcp =
        TcpDiscovery(
            context,
            { devices.value = listDevices() },
            { log(it) },
            scope,
        )
    private var scanner: BluetoothLeScanner? = null
    private var generation = 0L
    private var activeCallback: ScanCallback? = null

    private fun callback(token: Long) =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scope.launch {
                    if (generation != token || scanner == null || mode() != ConnectionMode.BLE)
                        return@launch
                    val device =
                        DeviceInfo(
                            "BLE:${result.device.address}",
                            result.scanRecord?.deviceName ?: "Android BLE Reader",
                            "BLE ${result.device.address}",
                        )
                    observed[device.deviceId] = device to android.os.SystemClock.elapsedRealtime()
                    devices.value = observed.values.map { it.first }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    if (generation == token)
                        failed(IllegalStateException("BLE scan failed code=$errorCode"))
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
                    devices.value = observed.values.map { it.first }
                }
            } finally {
                log("BLE expiry monitor stopped")
            }
        }
    }

    fun start(owner: Any, failure: (Throwable) -> Unit) {
        scope.launch {
            owners[owner] = failure
            if (scanner != null || tcp.isRunning) return@launch
            try {
                if (permissions().isNotEmpty())
                    throw SecurityException("Transport permissions required")
                if (mode() == ConnectionMode.TCP) {
                    devices.value = listDevices()
                    tcp.start(::failed)
                    return@launch
                }
                if (mode() != ConnectionMode.BLE) {
                    devices.value = listDevices()
                    return@launch
                }
                if (permissions().isNotEmpty())
                    throw SecurityException("Bluetooth permissions required")
                observed.clear()
                devices.value = emptyList()
                val active =
                    context
                        .getSystemService(BluetoothManager::class.java)
                        ?.adapter
                        ?.bluetoothLeScanner ?: error("BLE scanner unavailable")
                scanner = active
                val callback = callback(++generation)
                activeCallback = callback
                active.startScan(
                    listOf(
                        ScanFilter.Builder()
                            .setServiceUuid(ParcelUuid(BluetoothContract.SERVICE))
                            .build()
                    ),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    callback,
                )
                startExpiry(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed(error)
            }
        }
    }

    fun reset() {
        scope.launch {
            failed(IllegalStateException("Discovery configuration changed"))
            observed.clear()
            devices.value = emptyList()
        }
    }

    fun refresh() {
        scope.launch {
            if (mode() != ConnectionMode.BLE) devices.value = listDevices()
        }
    }

    fun stop(owner: Any) {
        scope.launch {
            owners.remove(owner)
            if (owners.isEmpty()) stopScan()
        }
    }

    private fun stopScan() {
        tcp.stop()
        generation++
        activeCallback?.let { callback -> runCatching { scanner?.stopScan(callback) } }
        activeCallback = null
        scanner = null
        expiryJob?.cancel()
        expiryJob = null
    }

    private fun failed(error: Throwable) {
        val listeners = owners.values.toList()
        owners.clear()
        stopScan()
        listeners.forEach { it(error) }
    }
}
