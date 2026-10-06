package com.hcebox.driver.androidnative

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.hcebox.cardreader.api.*
import kotlinx.coroutines.*

/** Exposes the remote Android NFC reader through the existing versioned AIDL API. */
class NativeDriverService : Service() {
    private val callbacks = RemoteCallbackList<IDriverCallback>()
    private val discoveries = RemoteCallbackList<IDiscoveryCallback>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false
    private val binder = object : ICardReaderDriver.Stub() {
        override fun getDriverInfo() = DriverInfo(CardReaderDriverContract.API_VERSION, "Android Native NFC Reader", "0.1.0", "HceBox", listOf("Android ISO-DEP Reader"))
        override fun getDriverStatus() = DriverStatus(DriverReadiness.READY, "Configure a TCP endpoint in the driver app")
        override fun startDiscovery(callback: IDiscoveryCallback?) {
            callback ?: return
            discoveries.register(callback)
            scope.launch {
                runCatching { callback.onDiscoveryStarted() }
                NativeController.devices().forEach { runCatching { callback.onDeviceFound(it) } }
            }
        }
        override fun stopDiscovery(callback: IDiscoveryCallback?) {
            callback ?: return
            discoveries.unregister(callback); scope.launch { runCatching { callback.onDiscoveryStopped() } }
        }
        override fun connectDevice(deviceId: String?, timeoutMs: Int): ConnectDeviceResult = try {
            NativeController.connect(deviceId, timeoutMs)
            ConnectDeviceResult.success(listOfNotNull(NativeController.reader()))
        } catch (error: Exception) { ConnectDeviceResult.failure(NativeController.failure(error)) }
        override fun disconnectDevice(deviceId: String?) { if (deviceId != null && NativeController.view.value.device?.deviceId == deviceId) NativeController.disconnect() }
        override fun listConnectedDevices() = listOfNotNull(NativeController.view.value.device)
        override fun getDeviceStatus(deviceId: String?): DeviceStatus? = NativeController.view.value.let {
            if (deviceId != null && it.device?.deviceId == deviceId) DeviceStatus(deviceId, DeviceConnectionState.CONNECTED, it.device.detail, it.error) else null
        }
        override fun listReaders() = listOfNotNull(NativeController.reader())
        override fun getReaderStatus(deviceId: String?, slotIndex: Int) = NativeController.readerStatus()?.takeIf { it.deviceId == deviceId && slotIndex == 0 }
        override fun selectReader(deviceId: String?, slotIndex: Int): DriverError? = try { NativeController.select(deviceId, slotIndex); null }
            catch (error: Exception) { NativeController.failure(error) }
        override fun unselectReader(deviceId: String?, slotIndex: Int) {
            runCatching { NativeController.unselect(deviceId, slotIndex) }.onFailure { NativeController.failure(it) }
        }
        override fun transmit(deviceId: String?, slotIndex: Int, commandApdu: ByteArray?, timeoutMs: Int): TransmitResult = try {
            TransmitResult.success(NativeController.transmit(deviceId, slotIndex, commandApdu, timeoutMs))
        } catch (error: Exception) { TransmitResult.failure(NativeController.failure(error)) }
        override fun registerDriverCallback(callback: IDriverCallback?) { callback?.let { callbacks.register(it) } }
        override fun unregisterDriverCallback(callback: IDriverCallback?) { callback?.let { callbacks.unregister(it) } }
    }
    override fun onCreate() {
        super.onCreate(); NativeController.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("connection", "Reader connection", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            var previous: NativeController.View? = null
            NativeController.view.collect { current ->
                val old = previous; previous = current
                if (current.device != null) {
                    try { promote() } catch (error: Exception) { NativeController.failure(error); NativeController.disconnect(); return@collect }
                    broadcast { it.onDeviceStatusChanged(DeviceStatus(current.device.deviceId, DeviceConnectionState.CONNECTED, current.device.detail, current.error)) }
                    NativeController.readerStatus()?.let { status -> broadcast { it.onReaderStatusChanged(status) } }
                } else {
                    old?.device?.let { device -> broadcast { it.onDeviceStatusChanged(DeviceStatus(device.deviceId, DeviceConnectionState.DISCONNECTED, null, current.error)) } }
                    if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); foreground = false }
                }
                if (old?.device != current.device) broadcast { it.onReadersChanged() }
            }
        }
    }
    private fun broadcast(action: (IDriverCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try { repeat(count) { runCatching { action(callbacks.getBroadcastItem(it)) } } } finally { callbacks.finishBroadcast() }
    }
    private fun promote() {
        if (foreground) return
        startService(Intent(this, NativeDriverService::class.java))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "connection").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Android NFC reader connected").setContentIntent(intent).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, notification)
        foreground = true
    }
    override fun onBind(intent: Intent?) = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() { scope.cancel(); callbacks.kill(); discoveries.kill(); super.onDestroy() }
}
