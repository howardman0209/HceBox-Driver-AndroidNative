package com.hcebox.driver.androidnative

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.hcebox.reader.protocol.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** GATT setup is included in the connection deadline; notifications feed shared reassembly. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
object BleClient {
    fun connect(
        context: Context,
        address: String,
        deadline: Deadline,
        log: (String) -> Unit,
    ): MessageChannel {
        val adapter =
            context.getSystemService(BluetoothManager::class.java).adapter
                ?: error("Bluetooth unavailable")
        val gattRef = AtomicReference<BluetoothGatt?>()
        val ready = CompletableFuture<Unit>()
        val ack = AtomicReference<CompletableFuture<Int>?>()
        val tx = AtomicReference<BluetoothGattCharacteristic?>()
        val rx = AtomicReference<BluetoothGattCharacteristic?>()
        val channel =
            FragmentChannel(
                { bytes, remaining ->
                    val gatt = gattRef.get() ?: throw ReaderFailure(5, "BLE disconnected")
                    val characteristic = rx.get() ?: throw ReaderFailure(5, "BLE RX unavailable")
                    val confirmation = CompletableFuture<Int>()
                    ack.set(confirmation)
                    try {
                        val started =
                            if (Build.VERSION.SDK_INT >= 33)
                                gatt.writeCharacteristic(
                                    characteristic,
                                    bytes,
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                                ) == BluetoothStatusCodes.SUCCESS
                            else {
                                characteristic.writeType =
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                characteristic.value = bytes
                                gatt.writeCharacteristic(characteristic)
                            }
                        if (
                            !started ||
                                confirmation.get(
                                    remaining.remaining().toLong(),
                                    TimeUnit.MILLISECONDS,
                                ) != BluetoothGatt.GATT_SUCCESS
                        )
                            throw ReaderFailure(5, "BLE write failed")
                    } finally {
                        ack.compareAndSet(confirmation, null)
                    }
                },
                {
                    ready.completeExceptionally(ReaderFailure(5, "BLE disconnected"))
                    ack.get()?.completeExceptionally(ReaderFailure(5, "BLE disconnected"))
                    gattRef.getAndSet(null)?.let {
                        it.disconnect()
                        it.close()
                    }
                },
                log,
            )
        val callback =
            object : BluetoothGattCallback() {
                fun failed(message: String) {
                    channel.fail(ReaderFailure(5, message))
                    ready.completeExceptionally(ReaderFailure(5, message))
                }

                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int,
                ) {
                    if (
                        status != BluetoothGatt.GATT_SUCCESS ||
                            newState == BluetoothProfile.STATE_DISCONNECTED
                    ) {
                        failed("BLE connection ended code=$status")
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        if (!gatt.discoverServices()) failed("BLE service discovery rejected")
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    val service = gatt.getService(BluetoothContract.SERVICE)
                    val inbound = service?.getCharacteristic(BluetoothContract.TX)
                    val outbound = service?.getCharacteristic(BluetoothContract.RX)
                    if (
                        status != BluetoothGatt.GATT_SUCCESS ||
                            inbound == null ||
                            outbound == null ||
                            !gatt.setCharacteristicNotification(inbound, true)
                    ) {
                        failed("Reader BLE service unavailable")
                        return
                    }
                    tx.set(inbound)
                    rx.set(outbound)
                    if (!gatt.requestMtu(247)) subscribe(gatt)
                }

                private fun subscribe(gatt: BluetoothGatt) {
                    val cccd = tx.get()?.getDescriptor(BluetoothContract.CCCD)
                    if (cccd == null) {
                        failed("Reader CCCD unavailable")
                        return
                    }
                    val accepted =
                        if (Build.VERSION.SDK_INT >= 33)
                            gatt.writeDescriptor(
                                cccd,
                                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
                            ) == BluetoothStatusCodes.SUCCESS
                        else {
                            cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                            gatt.writeDescriptor(cccd)
                        }
                    if (!accepted) failed("BLE subscription rejected")
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    if (
                        descriptor.uuid != BluetoothContract.CCCD ||
                            status != BluetoothGatt.GATT_SUCCESS
                    ) {
                        failed("BLE subscription failed")
                        return
                    }
                    // MTU negotiation is optional; default MTU works without it.
                    ready.complete(Unit)
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    log("BLE negotiated MTU=$mtu status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) channel.mtu = mtu.coerceIn(23, 517)
                    subscribe(gatt)
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (characteristic.uuid == BluetoothContract.RX) ack.get()?.complete(status)
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (characteristic.uuid == BluetoothContract.TX)
                        runCatching { channel.feed(value) }
                            .onFailure { failed("Invalid BLE frame") }
                }

                @Deprecated("Legacy Android callback")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT < 33)
                        onCharacteristicChanged(
                            gatt,
                            characteristic,
                            characteristic.value ?: byteArrayOf(),
                        )
                }
            }
        try {
            val gatt =
                adapter
                    .getRemoteDevice(address)
                    .connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw ReaderFailure(5, "GATT connection rejected")
            gattRef.set(gatt)
            if (ready.isCompletedExceptionally) {
                gatt.disconnect()
                gatt.close()
            }
            ready.get(deadline.remaining().toLong(), TimeUnit.MILLISECONDS)
            deadline.remaining()
            return channel
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }
}
