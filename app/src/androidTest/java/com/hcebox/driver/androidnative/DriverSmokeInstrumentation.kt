package com.hcebox.driver.androidnative

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.os.*
import com.hcebox.cardreader.api.*
import com.hcebox.reader.protocol.Apdu
import java.util.concurrent.*

/** Device smoke test using the platform instrumentation API without an extra test framework. */
class DriverSmokeInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        val results = Bundle()
        var bound = false
        var resultCode = Activity.RESULT_OK
        val service = CompletableFuture<ICardReaderDriver>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service.complete(ICardReaderDriver.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        NativeController.init(targetContext)
        val previousMode = NativeController.mode
        try {
            NativeController.disconnect()
            NativeController.setMode(arguments.getString("mode", "TCP"))
            if (NativeController.mode == "TCP") NativeController.configure(arguments.getString("host", NativeController.host), 35965)
            bound = targetContext.bindService(Intent(targetContext, NativeDriverService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Driver service binding failed" }
            val driver = service.get(5, TimeUnit.SECONDS)
            check(driver.driverInfo.apiVersion == CardReaderDriverContract.API_VERSION)
            check(driver.driverStatus.readiness == DriverReadiness.READY)
            val targetId = arguments.getString("deviceId")
            check(NativeController.mode != "CLASSIC" || targetId != null) { "Classic smoke test requires explicit deviceId" }
            val found = CompletableFuture<DeviceInfo>()
            val callback = object : IDiscoveryCallback.Stub() {
                override fun onDiscoveryStarted() {}
                override fun onDiscoveryStopped() {}
                override fun onDeviceFound(device: DeviceInfo) { if (targetId == null || targetId == device.deviceId) found.complete(device) }
                override fun onDeviceLost(id: String?) {}
                override fun onDiscoveryFailed(error: DriverError) { found.completeExceptionally(IllegalStateException(error.message)) }
            }
            driver.startDiscovery(callback)
            val device = found.get(10, TimeUnit.SECONDS)
            driver.stopDiscovery(callback)
            val changes = CountDownLatch(1)
            val updates = object : IDriverCallback.Stub() {
                override fun onReadersChanged() { if (driver.listReaders().isNotEmpty()) changes.countDown() }
                override fun onReaderStatusChanged(status: ReaderStatus) {}
                override fun onDeviceStatusChanged(status: DeviceStatus) {}
            }
            driver.registerDriverCallback(updates)
            val connected = driver.connectDevice(device.deviceId, 5000)
            check(connected.isSuccess) { "Connect failed: ${connected.error}" }
            check(changes.await(5, TimeUnit.SECONDS)) { "Reader change callback missing" }
            check(driver.listReaders().single().slotIndex == 0)
            check(driver.selectReader(device.deviceId, 0) == null) { "Select failed" }
            var status = driver.getReaderStatus(device.deviceId, 0)!!
            val testApdu = arguments.getString("apdu")
            if (testApdu != null) {
                val until = SystemClock.elapsedRealtime() + 10000
                while (!status.isCardPresent && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(100); status = driver.getReaderStatus(device.deviceId, 0)!!
                }
                check(status.isCardPresent) { "Present/re-present the test card on the reader" }
                val started = SystemClock.elapsedRealtime()
                val result = driver.transmit(device.deviceId, 0, Apdu.parseHex(testApdu), 2000)
                check(result.isSuccess) { "Real-card transmit failed: ${result.error}" }
                val response = result.responseApdu!!
                check(response.size >= 2)
                results.putString("apdu", "mode=${NativeController.mode} elapsedMs=${SystemClock.elapsedRealtime()-started} response=${Apdu.hex(response)}")
                arguments.getString("expected")?.let { check(Apdu.hex(response) == it.uppercase()) { "Response differs from local baseline" } }
            }
            check(status.isSelected)
            if (!status.isCardPresent) {
                val result = driver.transmit(device.deviceId, 0, byteArrayOf(0, 0xA4.toByte(), 4, 0, 0), 2000)
                check(result.error?.code == CardReaderErrorCode.CARD_ABSENT) { "No-card mapping failed: $result" }
            } else results.putString("card", "Present; no-card assertion skipped")
            driver.unselectReader(device.deviceId, 0)
            check(!driver.getReaderStatus(device.deviceId, 0)!!.isSelected)
            driver.disconnectDevice(device.deviceId)
            check(driver.listReaders().isEmpty() && driver.listConnectedDevices().isEmpty())
            driver.unregisterDriverCallback(updates)
            val detail = results.getString("apdu") ?: results.getString("card") ?: "No-card mapping tested"
            results.putString("stream", "AIDL discovery/connect/callback/select/transmit/unselect/disconnect passed via ${NativeController.mode}\n$detail\n")
        } catch (error: Throwable) {
            results.putString("error", error.stackTraceToString()); resultCode = Activity.RESULT_CANCELED
        } finally {
            NativeController.disconnect(); NativeController.setMode(previousMode)
            if (bound) targetContext.unbindService(connection)
        }
        finish(resultCode, results)
    }
}
