package com.hcebox.driver.androidnative.connection

import android.content.Context
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ble.BleScanner
import com.hcebox.driver.androidnative.connection.classic.ClassicScanner
import com.hcebox.driver.androidnative.connection.tcp.TcpScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shared scan owner for UI and AIDL callers; the selected mode's [ReaderScanner] does the scanning.
 * The owner supplies a queued Main scope and must stop scans before cancelling it.
 */
class ReaderDiscovery(
    private val context: Context,
    private val mode: () -> ConnectionMode,
    private val permissions: () -> Array<String>,
    private val listDevices: () -> List<DeviceInfo>,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
    private val ready: suspend () -> Unit = {},
) {
    val devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val owners = mutableMapOf<Any, (Throwable) -> Unit>()
    val tcp =
        TcpScanner(
            context,
            { devices.value = listDevices() },
            { log(it) },
            scope,
        )
    private val classic = ClassicScanner { devices.value = listDevices() }
    // Late scan results are dropped once another mode is selected.
    private val ble =
        BleScanner(context, { if (mode() == ConnectionMode.BLE) devices.value = it }, log, scope)

    private fun scanner(mode: ConnectionMode): ReaderScanner =
        when (mode) {
            ConnectionMode.TCP -> tcp
            ConnectionMode.CLASSIC -> classic
            ConnectionMode.BLE -> ble
        }

    fun start(owner: Any, failure: (Throwable) -> Unit) {
        scope.launch {
            owners[owner] = failure
            try {
                ready()
                // A stop/reset during initialization must not start a retired scan.
                if (owners[owner] !== failure) return@launch
                if (ConnectionMode.entries.any { scanner(it).isRunning }) return@launch
                if (permissions().isNotEmpty())
                    throw SecurityException("Transport permissions required")
                scanner(mode()).start(::failed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (owners[owner] === failure) failed(error)
            }
        }
    }

    fun reset() {
        scope.launch {
            failed(IllegalStateException("Discovery configuration changed"))
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
        ConnectionMode.entries.forEach { scanner(it).stop() }
    }

    private fun failed(error: Throwable) {
        val listeners = owners.values.toList()
        owners.clear()
        stopScan()
        listeners.forEach { it(error) }
    }
}
