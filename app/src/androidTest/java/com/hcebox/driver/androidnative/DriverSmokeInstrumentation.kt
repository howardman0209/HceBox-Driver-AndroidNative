package com.hcebox.driver.androidnative

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.os.*
import com.hcebox.cardreader.api.*
import com.hcebox.driver.androidnative.connection.NativeController
import com.hcebox.reader.protocol.Apdu
import com.hcebox.reader.protocol.Status
import java.util.concurrent.*

/** Device smoke test using the platform instrumentation API without an extra test framework. */
class DriverSmokeInstrumentation : Instrumentation() {
    private var arguments = Bundle()

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val results = Bundle()
        var bound = false
        var resultCode = Activity.RESULT_OK
        val service = CompletableFuture<ICardReaderDriver>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service.complete(ICardReaderDriver.Stub.asInterface(binder))
                }

                override fun onServiceDisconnected(name: ComponentName?) {}
            }
        val controller = targetContext.nativeController
        val previousMode = controller.mode
        try {
            verifyRemovalStatusMapping()
            controller.disconnect()
            controller.setMode(arguments.getString("mode", "TCP"))
            val nsd = arguments.getString("nsd") == "true"
            if (controller.mode == "TCP" && !nsd)
                controller.configure(arguments.getString("host", controller.host), 35965)
            bound =
                targetContext.bindService(
                    Intent(targetContext, NativeDriverService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            check(bound) { "Driver service binding failed" }
            val driver = service.get(5, TimeUnit.SECONDS)
            check(driver.driverInfo.apiVersion == CardReaderDriverContract.API_VERSION)
            check(driver.driverStatus.readiness == DriverReadiness.READY)
            val targetId = arguments.getString("deviceId")
            check(controller.mode != "CLASSIC" || targetId != null) {
                "Classic smoke test requires explicit deviceId"
            }
            val found = CompletableFuture<DeviceInfo>()
            val callback =
                object : IDiscoveryCallback.Stub() {
                    override fun onDiscoveryStarted() {}

                    override fun onDiscoveryStopped() {}

                    override fun onDeviceFound(device: DeviceInfo) {
                        if (
                            (targetId == null || targetId == device.deviceId) &&
                                (!nsd || device.deviceId.startsWith("TCP:NSD:"))
                        )
                            found.complete(device)
                    }

                    override fun onDeviceLost(id: String?) {}

                    override fun onDiscoveryFailed(error: DriverError) {
                        found.completeExceptionally(IllegalStateException(error.message))
                    }
                }
            driver.startDiscovery(callback)
            val device = found.get(10, TimeUnit.SECONDS)
            driver.stopDiscovery(callback)
            val changes = CountDownLatch(1)
            val removal = CountDownLatch(1)
            val updates =
                object : IDriverCallback.Stub() {
                    override fun onReadersChanged() {
                        if (driver.listReaders().isNotEmpty()) changes.countDown()
                    }

                    override fun onReaderStatusChanged(status: ReaderStatus) {
                        if (
                            !status.isCardPresent &&
                                status.isSelected &&
                                status.lastError?.code == CardReaderErrorCode.CARD_REMOVED
                        )
                            removal.countDown()
                    }

                    override fun onDeviceStatusChanged(status: DeviceStatus) {}
                }
            driver.registerDriverCallback(updates)
            val connected = driver.connectDevice(device.deviceId, 5000)
            check(connected.isSuccess) { "Connect failed: ${connected.error}" }
            check(changes.await(5, TimeUnit.SECONDS)) { "Reader change callback missing" }
            check(driver.listReaders().single().slotIndex == 0)
            check(controller.view.value.device?.deviceId == device.deviceId) {
                "Service and workbench must share the Application-owned controller"
            }
            check(driver.selectReader(device.deviceId, 0) == null) { "Select failed" }
            var status = driver.getReaderStatus(device.deviceId, 0)!!
            val testApdu = arguments.getString("apdu")
            val idleRemoval = arguments.getString("idleRemoval") == "true"
            check(!idleRemoval || testApdu == null) { "Idle removal test must not send an APDU" }
            if (idleRemoval) {
                val until = SystemClock.elapsedRealtime() + 10000
                while (!status.isCardPresent && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(100)
                    status = driver.getReaderStatus(device.deviceId, 0)!!
                }
                check(status.isCardPresent) { "Present test card before idle-removal verification" }
                val armed = SystemClock.elapsedRealtime()
                sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Idle removal test armed: remove the card now; no APDU will be sent.\n",
                        )
                    },
                )
                check(removal.await(45, TimeUnit.SECONDS)) {
                    "No CARD_REMOVED callback within 45 seconds"
                }
                status = driver.getReaderStatus(device.deviceId, 0)!!
                check(
                    !status.isCardPresent &&
                        status.isSelected &&
                        status.lastError?.code == CardReaderErrorCode.CARD_REMOVED
                )
                check(driver.listConnectedDevices().single().deviceId == device.deviceId) {
                    "Removal must preserve the transport"
                }
                results.putString(
                    "idleRemoval",
                    "Idle CARD_REMOVED callback passed; selected/connected retained; no APDU sent. Wait since arming=${SystemClock.elapsedRealtime()-armed}ms (includes human removal time).",
                )
            }
            if (testApdu != null) {
                val until = SystemClock.elapsedRealtime() + 10000
                while (!status.isCardPresent && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(100)
                    status = driver.getReaderStatus(device.deviceId, 0)!!
                }
                check(status.isCardPresent) { "Present/re-present the test card on the reader" }
                val started = SystemClock.elapsedRealtime()
                val result = driver.transmit(device.deviceId, 0, Apdu.parseHex(testApdu), 2000)
                check(result.isSuccess) { "Real-card transmit failed: ${result.error}" }
                val response = result.responseApdu!!
                check(response.size >= 2)
                results.putString(
                    "apdu",
                    "mode=${controller.mode} elapsedMs=${SystemClock.elapsedRealtime()-started} response=${Apdu.hex(response)}",
                )
                arguments.getString("expected")?.let {
                    check(Apdu.hex(response) == it.uppercase()) {
                        "Response differs from local baseline"
                    }
                }
            }
            check(status.isSelected)
            if (arguments.getString("heartbeat") == "true") {
                Thread.sleep(11000)
                check(driver.listConnectedDevices().single().deviceId == device.deviceId) {
                    "Heartbeat must retain the connection"
                }
            }
            if (!status.isCardPresent && !idleRemoval) {
                val result =
                    driver.transmit(
                        device.deviceId,
                        0,
                        byteArrayOf(0, 0xA4.toByte(), 4, 0, 0),
                        2000,
                    )
                check(
                    result.error?.code ==
                        if (status.lastError?.code == CardReaderErrorCode.CARD_REMOVED)
                            CardReaderErrorCode.CARD_REMOVED
                        else CardReaderErrorCode.CARD_ABSENT
                ) {
                    "No-card mapping failed: $result"
                }
            } else if (!idleRemoval) results.putString("card", "Present; no-card assertion skipped")
            driver.unselectReader(device.deviceId, 0)
            check(!driver.getReaderStatus(device.deviceId, 0)!!.isSelected)
            driver.disconnectDevice(device.deviceId)
            check(driver.listReaders().isEmpty() && driver.listConnectedDevices().isEmpty())
            if (nsd) {
                // Reconnect without scanning again to exercise retained NSD identity.
                repeat(2) {
                    check(controller.devices().any { it.deviceId == device.deviceId }) {
                        "NSD device must remain selectable"
                    }
                    val reconnected = driver.connectDevice(device.deviceId, 5000)
                    check(reconnected.isSuccess) {
                        "NSD reconnect ${it + 1} failed: ${reconnected.error}"
                    }
                    check(driver.selectReader(device.deviceId, 0) == null) {
                        "Select after NSD reconnect failed"
                    }
                    driver.unselectReader(device.deviceId, 0)
                    driver.disconnectDevice(device.deviceId)
                    check(driver.listReaders().isEmpty() && driver.listConnectedDevices().isEmpty())
                }
                results.putString("reconnect", "Two NSD reconnects without rediscovery passed")
            }
            driver.unregisterDriverCallback(updates)
            val detail =
                results.getString("idleRemoval")
                    ?: results.getString("apdu")
                    ?: results.getString("card")
                    ?: "No-card mapping tested"
            results.putString(
                "stream",
                "AIDL discovery/connect/callback/select/transmit/unselect/disconnect passed via ${controller.mode}${if (nsd) " NSD" else ""}\n$detail\n",
            )
        } catch (error: Throwable) {
            results.putString("error", error.stackTraceToString())
            resultCode = Activity.RESULT_CANCELED
        } finally {
            controller.disconnect()
            controller.setMode(previousMode)
            if (bound) targetContext.unbindService(connection)
        }
        finish(resultCode, results)
    }

    private fun verifyRemovalStatusMapping() {
        // Isolated fixture exercises the AIDL snapshot mapping without signalling a real reader.
        val fixture = NativeController(targetContext)
        val device = DeviceInfo("fixture", "Removal fixture", null)
        for ((wire, expected) in
            listOf(
                2 to CardReaderErrorCode.CARD_REMOVED,
                3 to CardReaderErrorCode.TIMEOUT,
                5 to CardReaderErrorCode.TRANSPORT,
            )) {
            fixture.view.value =
                NativeController.View(device, Status(selected = true, lastError = wire))
            val status = fixture.readerStatus()!!
            check(!status.isCardPresent && status.isSelected && status.lastError?.code == expected)
        }
        fixture.view.value = NativeController.View(device, Status(1, true, true, false, 261))
        check(fixture.readerStatus()!!.lastError == null) { "Fresh card must clear removal reason" }
    }
}
