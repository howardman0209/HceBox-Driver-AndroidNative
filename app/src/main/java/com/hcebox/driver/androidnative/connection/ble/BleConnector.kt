package com.hcebox.driver.androidnative.connection.ble

import android.content.Context
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ConnectAttempt
import com.hcebox.driver.androidnative.connection.ReaderConnector
import com.hcebox.reader.protocol.core.Deadline

/** Live scan results; GATT setup is deadline-bounded and interruptible, so nothing is tracked. */
class BleConnector(
    private val context: Context,
    private val scanned: () -> List<DeviceInfo>,
    private val log: (String) -> Unit,
) : ReaderConnector {
    override fun devices() = scanned()

    override fun open(device: DeviceInfo, deadline: Deadline, attempt: ConnectAttempt) =
        BleClient.connect(context, device.deviceId.removePrefix("BLE:"), deadline, log)
}
