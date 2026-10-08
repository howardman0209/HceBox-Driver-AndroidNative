package com.hcebox.driver.androidnative

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.hcebox.cardreader.api.*
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.connection.NativeController
import com.hcebox.driver.androidnative.setup.missingPermissions
import kotlinx.coroutines.*

/** Exposes the remote Android NFC reader through the existing versioned AIDL API. */
class NativeDriverService : Service() {
    private val controller
        get() = nativeController

    private val callbacks = RemoteCallbackList<IDriverCallback>()
    private val discoveries =
        object : RemoteCallbackList<IDiscoveryCallback>() {
            override fun onCallbackDied(callback: IDiscoveryCallback) {
                controller.discovery.stop(callback.asBinder())
            }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false
    private val binder =
        object : ICardReaderDriver.Stub() {
            override fun getDriverInfo() =
                DriverInfo(
                    CardReaderDriverContract.API_VERSION,
                    "Android Native NFC Reader",
                    BuildConfig.VERSION_NAME,
                    "HceBox",
                    listOf("Android ISO-DEP Reader"),
                )

            override fun getDriverStatus(): DriverStatus {
                val loaded = controller.preferences.state.value
                val settings = loaded.settings
                return if (settings == null || loaded.error != null ||
                    (settings.mode == ConnectionMode.REMOTE && settings.remoteOrigin.isBlank()))
                    DriverStatus(DriverReadiness.UNAVAILABLE)
                else if (missingPermissions(this@NativeDriverService, settings.mode).isEmpty())
                    DriverStatus(DriverReadiness.READY)
                else DriverStatus(DriverReadiness.PERMISSION_REQUIRED)
            }

            override fun startDiscovery(callback: IDiscoveryCallback?) {
                callback ?: return
                discoveries.register(callback)
                scope.launch {
                    runCatching { callback.onDiscoveryStarted() }
                    controller.discovery.start(callback.asBinder()) { error ->
                        discoveries.unregister(callback)
                        runCatching { callback.onDiscoveryFailed(controller.failure(error)) }
                    }
                    controller.discovery.devices.value.forEach {
                        runCatching { callback.onDeviceFound(it) }
                    }
                }
            }

            override fun stopDiscovery(callback: IDiscoveryCallback?) {
                callback ?: return
                controller.discovery.stop(callback.asBinder())
                discoveries.unregister(callback)
                scope.launch { runCatching { callback.onDiscoveryStopped() } }
            }

            override fun connectDevice(deviceId: String?, timeoutMs: Int): ConnectDeviceResult =
                try {
                    controller.connect(deviceId, timeoutMs)
                    ConnectDeviceResult.success(listOfNotNull(controller.reader()))
                } catch (error: Exception) {
                    ConnectDeviceResult.failure(controller.failure(error))
                }

            override fun disconnectDevice(deviceId: String?) {
                if (deviceId != null && controller.view.value.device?.deviceId == deviceId)
                    controller.disconnect()
            }

            override fun listConnectedDevices() = listOfNotNull(controller.view.value.device)

            override fun getDeviceStatus(deviceId: String?): DeviceStatus? =
                controller.view.value.let {
                    if (deviceId != null && it.device?.deviceId == deviceId)
                        DeviceStatus(
                            deviceId,
                            DeviceConnectionState.CONNECTED,
                            it.device.detail,
                            it.error,
                        )
                    else null
                }

            override fun listReaders() = listOfNotNull(controller.reader())

            override fun getReaderStatus(deviceId: String?, slotIndex: Int) =
                controller.readerStatus()?.takeIf { it.deviceId == deviceId && slotIndex == 0 }

            override fun selectReader(deviceId: String?, slotIndex: Int): DriverError? =
                try {
                    controller.select(deviceId, slotIndex)
                    null
                } catch (error: Exception) {
                    controller.failure(error)
                }

            override fun unselectReader(deviceId: String?, slotIndex: Int) {
                runCatching { controller.unselect(deviceId, slotIndex) }
                    .onFailure { controller.failure(it) }
            }

            override fun transmit(
                deviceId: String?,
                slotIndex: Int,
                commandApdu: ByteArray?,
                timeoutMs: Int,
            ): TransmitResult =
                try {
                    TransmitResult.success(
                        controller.transmit(deviceId, slotIndex, commandApdu, timeoutMs)
                    )
                } catch (error: Exception) {
                    TransmitResult.failure(controller.failure(error))
                }

            override fun registerDriverCallback(callback: IDriverCallback?) {
                callback?.let { callbacks.register(it) }
            }

            override fun unregisterDriverCallback(callback: IDriverCallback?) {
                callback?.let { callbacks.unregister(it) }
            }
        }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    "connection",
                    "Reader connection",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        scope.launch {
            var previous = emptyMap<String, DeviceInfo>()
            controller.discovery.devices.collect { found ->
                val current = found.associateBy { it.deviceId }
                val count = discoveries.beginBroadcast()
                try {
                    repeat(count) { index ->
                        val callback = discoveries.getBroadcastItem(index)
                        (previous.keys - current.keys).forEach {
                            runCatching { callback.onDeviceLost(it) }
                        }
                        current.values
                            .filter { previous[it.deviceId] != it }
                            .forEach { runCatching { callback.onDeviceFound(it) } }
                    }
                } finally {
                    discoveries.finishBroadcast()
                }
                previous = current
            }
        }
        scope.launch {
            var previous: NativeController.View? = null
            controller.view.collect { current ->
                val old = previous
                previous = current
                if (current.device != null) {
                    try {
                        promote()
                    } catch (error: Exception) {
                        controller.failure(error)
                        controller.disconnect()
                        return@collect
                    }
                    broadcast {
                        it.onDeviceStatusChanged(
                            DeviceStatus(
                                current.device.deviceId,
                                DeviceConnectionState.CONNECTED,
                                current.device.detail,
                                current.error,
                            )
                        )
                    }
                    controller.readerStatus()?.let { status ->
                        broadcast { it.onReaderStatusChanged(status) }
                    }
                } else {
                    old?.device?.let { device ->
                        broadcast {
                            it.onDeviceStatusChanged(
                                DeviceStatus(
                                    device.deviceId,
                                    DeviceConnectionState.DISCONNECTED,
                                    null,
                                    current.error,
                                )
                            )
                        }
                    }
                    if (foreground) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        foreground = false
                    }
                }
                if (old?.device != current.device) broadcast { it.onReadersChanged() }
            }
        }
    }

    private fun broadcast(action: (IDriverCallback) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { runCatching { action(callbacks.getBroadcastItem(it)) } }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun promote() {
        if (foreground) return
        startService(Intent(this, NativeDriverService::class.java))
        val intent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(this, "connection")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Android NFC reader connected")
                .setContentIntent(intent)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1, notification)
        foreground = true
    }

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        val count = discoveries.beginBroadcast()
        try {
            repeat(count) { controller.discovery.stop(discoveries.getBroadcastItem(it).asBinder()) }
        } finally {
            discoveries.finishBroadcast()
        }
        controller.disconnect()
        scope.cancel()
        callbacks.kill()
        discoveries.kill()
        super.onDestroy()
    }
}
