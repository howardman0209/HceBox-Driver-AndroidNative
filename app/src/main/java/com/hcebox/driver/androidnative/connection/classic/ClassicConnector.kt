package com.hcebox.driver.androidnative.connection.classic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ConnectAttempt
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.connection.ReaderConnector
import com.hcebox.driver.androidnative.setup.missingPermissions
import com.hcebox.reader.protocol.channel.MessageChannel
import com.hcebox.reader.protocol.channel.StreamChannel
import com.hcebox.reader.protocol.contract.BluetoothContract
import com.hcebox.reader.protocol.core.Deadline

/** Bonded devices over secure RFCOMM; the fixed service UUID and HELLO prove compatibility. */
@SuppressLint("MissingPermission")
class ClassicConnector(private val context: Context) : ReaderConnector {
    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun devices(): List<DeviceInfo> {
        if (missingPermissions(context, ConnectionMode.CLASSIC).isNotEmpty())
            throw SecurityException("Bluetooth permissions required")
        val adapter = adapter()
        check(adapter?.isEnabled == true) { "Bluetooth disabled" }
        return adapter.bondedDevices.map {
            DeviceInfo(
                "CLASSIC:${it.address}",
                it.name ?: "Bluetooth device",
                "Classic bonded device",
            )
        }
    }

    override fun open(
        device: DeviceInfo,
        deadline: Deadline,
        attempt: ConnectAttempt,
    ): MessageChannel {
        val adapter = adapter() ?: error("Bluetooth unavailable")
        adapter.cancelDiscovery()
        val socket =
            adapter
                .getRemoteDevice(device.deviceId.removePrefix("CLASSIC:"))
                .createRfcommSocketToServiceRecord(BluetoothContract.CLASSIC)
        // RFCOMM connect ignores interrupts; a timeout unblocks it by closing the tracked socket.
        attempt.track(socket)
        socket.connect()
        deadline.remaining()
        return StreamChannel(socket.inputStream, socket.outputStream) { socket.close() }
    }
}
