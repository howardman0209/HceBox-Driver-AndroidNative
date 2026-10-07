package com.hcebox.driver.androidnative.connection

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.cardreader.api.DriverError
import com.hcebox.cardreader.api.ReaderInfo
import com.hcebox.cardreader.api.ReaderStatus
import com.hcebox.driver.androidnative.connection.ble.BleClient
import com.hcebox.driver.androidnative.connection.tcp.TcpEndpoint
import com.hcebox.driver.androidnative.driver.DriverStatusMapper
import com.hcebox.driver.androidnative.setup.missingPermissions
import com.hcebox.reader.protocol.channel.MessageChannel
import com.hcebox.reader.protocol.channel.StreamChannel
import com.hcebox.reader.protocol.codec.Apdu
import com.hcebox.reader.protocol.contract.BluetoothContract
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.ReaderFailure
import com.hcebox.reader.protocol.model.Status
import com.hcebox.reader.protocol.session.ReaderClient
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide connection owner shared by the workbench and bound service. */
class NativeController(context: Context) {
    data class View(
        val device: DeviceInfo? = null,
        val status: Status = Status(),
        val error: DriverError? = null,
    )

    val view = MutableStateFlow(View())
    val notes = MutableStateFlow("Ready")
    private val context = context.applicationContext
    val discovery =
        ReaderDiscovery(
            this.context,
            mode = { mode },
            permissions = { missingPermissions(this.context, mode) },
            listDevices = ::devices,
            log = ::log,
        )
    private val lock = Any()
    private val connectGate = Semaphore(1)
    private var generation = 0L
    private var client: ReaderClient? = null
    private val workers = Executors.newCachedThreadPool {
        Thread(it, "NativeConnect").apply { isDaemon = true }
    }
    private val prefs
        get() = context.getSharedPreferences("driver", Context.MODE_PRIVATE)

    val mode
        get() = prefs.getString("mode", "TCP") ?: "TCP"

    fun setMode(value: String) {
        require(value in listOf("TCP", "CLASSIC", "BLE"))
        check(view.value.device == null)
        disconnect()
        discovery.reset()
        prefs.edit { putString("mode", value) }
    }

    val host
        get() = prefs.getString("host", "") ?: ""

    val port
        get() = prefs.getInt("port", 35965)

    fun configure(host: String, port: Int) {
        require(host.isNotBlank() && port in 1..65535) { "Valid host and port required" }
        check(view.value.device == null) { "Disconnect before editing endpoint" }
        prefs.edit {
            putString("host", host.trim())
            putInt("port", port)
        }
        discovery.refresh()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun devices(): List<DeviceInfo> {
        if (mode == "BLE") return discovery.devices.value
        if (mode != "TCP") {
            if (missingPermissions(context, mode).isNotEmpty())
                throw SecurityException("Bluetooth permissions required")
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            check(adapter?.isEnabled == true) { "Bluetooth disabled" }
            return adapter.bondedDevices.map {
                DeviceInfo(
                    "CLASSIC:${it.address}",
                    it.name ?: "Bluetooth device",
                    "Classic bonded device",
                )
            }
        }
        return (listOfNotNull(manualEndpoint()?.device) +
                discovery.tcp.endpoints().map { it.device })
            .distinctBy { it.deviceId }
    }

    private fun manualEndpoint(): TcpEndpoint? {
        if (host.isBlank()) return null
        val id =
            prefs.getString("endpoint", null)
                ?: UUID.randomUUID().toString().also { prefs.edit { putString("endpoint", it) } }
        return TcpEndpoint(
            DeviceInfo("TCP:$id", "Manual Android NFC Reader", "TCP $host:$port (manual)"),
            listOf(host),
            port,
        )
    }

    fun log(message: String) {
        Log.d("NativeDriver", message)
        notes.value = (message + "\n" + notes.value).take(3000)
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun connect(id: String?, timeoutMs: Int) {
        val deadline = Deadline(timeoutMs)
        if (!connectGate.tryAcquire()) throw ReaderFailure(6, "Connection in progress")
        var candidate: ReaderClient? = null
        val token: Long
        try {
            synchronized(lock) {
                if (view.value.device?.deviceId == id && client != null) return
                if (client != null) throw ReaderFailure(6, "Another device connected")
                token = ++generation
            }
            val device =
                devices().firstOrNull { it.deviceId == id }
                    ?: throw IllegalArgumentException("Device not found")
            if (missingPermissions(context, mode).isNotEmpty())
                throw SecurityException("Transport permissions required")
            val selectedMode = mode
            val manual =
                if (selectedMode == "TCP" && !device.deviceId.startsWith("TCP:NSD:"))
                    manualEndpoint()?.takeIf { it.device.deviceId == device.deviceId }
                else null
            val expectedInstallationId =
                if (device.deviceId.startsWith("TCP:NSD:")) device.deviceId.removePrefix("TCP:NSD:")
                else null
            val socket = java.util.concurrent.atomic.AtomicReference<Socket?>()
            val bluetooth =
                if (selectedMode == "CLASSIC") {
                    if (missingPermissions(context, mode).isNotEmpty())
                        throw SecurityException("Bluetooth permissions required")
                    val adapter =
                        context.getSystemService(BluetoothManager::class.java)?.adapter
                            ?: error("Bluetooth unavailable")
                    adapter.cancelDiscovery()
                    adapter
                        .getRemoteDevice(device.deviceId.removePrefix("CLASSIC:"))
                        .createRfcommSocketToServiceRecord(BluetoothContract.CLASSIC)
                } else null
            val future =
                workers.submit<MessageChannel> {
                    try {
                        if (selectedMode == "BLE")
                            return@submit BleClient.connect(
                                context,
                                device.deviceId.removePrefix("BLE:"),
                                deadline,
                                ::log,
                            )
                        if (bluetooth != null) {
                            bluetooth.connect()
                            deadline.remaining()
                            return@submit StreamChannel(
                                bluetooth.inputStream,
                                bluetooth.outputStream,
                            ) {
                                bluetooth.close()
                            }
                        }
                        val endpoint =
                            if (device.deviceId.startsWith("TCP:NSD:"))
                                discovery.tcp.refresh(device.deviceId, deadline)
                            else manual ?: throw IllegalArgumentException("TCP endpoint not found")
                        var lastError: java.io.IOException? = null
                        for (host in endpoint.hosts) for (address in
                            java.net.InetAddress.getAllByName(host)) {
                            deadline.remaining()
                            val connection =
                                endpoint.network?.socketFactory?.createSocket() ?: Socket()
                            socket.set(connection)
                            try {
                                if (Thread.currentThread().isInterrupted)
                                    throw java.io.InterruptedIOException("Connection cancelled")
                                connection.connect(
                                    InetSocketAddress(address, endpoint.port),
                                    deadline.remaining(),
                                )
                                return@submit StreamChannel(connection)
                            } catch (error: java.io.IOException) {
                                connection.close()
                                lastError = error
                            }
                        }
                        throw lastError ?: java.io.IOException("TCP endpoint has no usable address")
                    } catch (error: Throwable) {
                        socket.get()?.close()
                        runCatching { bluetooth?.close() }
                        throw error
                    }
                }
            val channel =
                try {
                    future.get(deadline.remaining().toLong(), TimeUnit.MILLISECONDS)
                } catch (error: Exception) {
                    socket.get()?.close()
                    runCatching { bluetooth?.close() }
                    future.cancel(true)
                    throw error
                }
            candidate =
                ReaderClient(
                    channel,
                    { status ->
                        synchronized(lock) {
                            if (generation == token && view.value.device != null) {
                                if (
                                    status.lastError != 0 &&
                                        status.lastError != view.value.status.lastError
                                )
                                    log(
                                        "Reader status error=${DriverStatusMapper.cardError(status.lastError).code}"
                                    )
                                view.value = view.value.copy(status = status)
                            }
                        }
                    },
                    { error ->
                        synchronized(lock) {
                            if (generation == token) {
                                client = null
                                view.value = View(error = error?.let(::failure))
                                log(
                                    "Disconnected: ${if (error == null) "requested" else error.message ?: error.javaClass.simpleName}"
                                )
                            }
                        }
                    },
                    ::log,
                )
            candidate.handshake(deadline)
            check(
                expectedInstallationId == null ||
                    candidate.hello?.installationId == expectedInstallationId
            ) {
                "Reader identity differs from NSD announcement"
            }
            synchronized(lock) {
                deadline.remaining()
                if (token != generation || !candidate.isOpen())
                    throw ReaderFailure(5, "Connection cancelled")
                client = candidate
                view.value = View(device, candidate.status)
            }
            log("Connected ${device.detail}")
        } catch (error: Throwable) {
            candidate?.close()
            throw error
        } finally {
            connectGate.release()
        }
    }

    fun disconnect() {
        val old =
            synchronized(lock) {
                generation++
                client.also {
                    client = null
                    view.value = View()
                }
            }
        old?.close()
        log("Disconnected")
    }

    private fun connection(id: String?, slot: Int): ReaderClient =
        synchronized(lock) {
            if (slot != 0 || view.value.device?.deviceId != id || client == null)
                throw ReaderFailure(8, "Reader not connected")
            client!!
        }

    fun select(id: String?, slot: Int) {
        connection(id, slot).select()
    }

    fun unselect(id: String?, slot: Int) {
        connection(id, slot).unselect()
    }

    fun transmit(id: String?, slot: Int, command: ByteArray?, timeout: Int): ByteArray {
        val deadline = Deadline(timeout)
        if (command == null || !Apdu.valid(command)) throw ReaderFailure(4, "Malformed APDU")
        return connection(id, slot).transmit(command, deadline)
    }

    fun reader(): ReaderInfo? = view.value.device?.let(DriverStatusMapper::reader)

    fun readerStatus(): ReaderStatus? =
        view.value.let { state ->
            state.device?.let { DriverStatusMapper.readerStatus(it, state.status) }
        }

    fun failure(error: Throwable): DriverError {
        val result = DriverStatusMapper.failure(error)
        log("Operation failed code=${result.code} reason=${result.message}")
        return result
    }
}
