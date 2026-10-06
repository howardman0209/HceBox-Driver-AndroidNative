package com.hcebox.driver.androidnative

import android.content.Context
import android.bluetooth.BluetoothManager
import android.util.Log
import com.hcebox.cardreader.api.*
import com.hcebox.reader.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.*

/** Process-wide connection owner shared by the workbench and bound service. */
object NativeController {
    data class View(val device: DeviceInfo? = null, val status: Status = Status(), val error: DriverError? = null)
    val view = MutableStateFlow(View())
    val notes = MutableStateFlow("Ready")
    private lateinit var context: Context
    lateinit var discovery: ReaderDiscovery; private set
    private val lock = Any()
    private val connectGate = Semaphore(1)
    private var generation = 0L
    private var client: ReaderClient? = null
    private val workers = Executors.newCachedThreadPool { Thread(it, "NativeConnect").apply { isDaemon = true } }
    fun init(value: Context) { synchronized(lock) { if (!::context.isInitialized) { context = value.applicationContext; discovery = ReaderDiscovery(context) } } }
    private val prefs get() = context.getSharedPreferences("driver", Context.MODE_PRIVATE)
    val mode get() = prefs.getString("mode", "TCP") ?: "TCP"
    fun setMode(value: String) {
        require(value in listOf("TCP", "CLASSIC", "BLE")); check(view.value.device == null)
        disconnect(); discovery.reset(); prefs.edit().putString("mode", value).apply()
    }
    val host get() = prefs.getString("host", "") ?: ""
    val port get() = prefs.getInt("port", 35965)
    fun configure(host: String, port: Int) {
        require(host.isNotBlank() && port in 1..65535) { "Valid host and port required" }
        check(view.value.device == null) { "Disconnect before editing endpoint" }
        prefs.edit().putString("host", host.trim()).putInt("port", port).apply()
        discovery.refresh()
    }
    @android.annotation.SuppressLint("MissingPermission")
    fun devices(): List<DeviceInfo> {
        if (mode == "BLE") return discovery.devices.value
        if (mode != "TCP") {
            if (missingPermissions(context).isNotEmpty()) throw SecurityException("Bluetooth permissions required")
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            check(adapter?.isEnabled == true) { "Bluetooth disabled" }
            return adapter.bondedDevices.map { DeviceInfo("CLASSIC:${it.address}", it.name ?: "Bluetooth device", "Classic bonded device") }
        }
        if (host.isBlank()) return emptyList()
        val id = prefs.getString("endpoint", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("endpoint", it).apply() }
        return listOf(DeviceInfo("TCP:$id", "Android NFC Reader", "TCP $host:$port (configured)"))
    }
    fun log(message: String) { Log.d("NativeDriver", message); notes.value = (message + "\n" + notes.value).take(3000) }
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
            val device = devices().firstOrNull { it.deviceId == id } ?: throw IllegalArgumentException("Device not found")
            val selectedMode = mode
            val selectedHost = host; val selectedPort = port
            val socket = Socket()
            val bluetooth = if (selectedMode == "CLASSIC") {
                if (missingPermissions(context).isNotEmpty()) throw SecurityException("Bluetooth permissions required")
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth unavailable")
                adapter.cancelDiscovery()
                adapter.getRemoteDevice(device.deviceId.removePrefix("CLASSIC:")).createRfcommSocketToServiceRecord(BluetoothContract.CLASSIC)
            } else null
            val future = workers.submit<MessageChannel> {
                try {
                    if (selectedMode == "BLE") return@submit BleClient.connect(context, device.deviceId.removePrefix("BLE:"), deadline)
                    if (bluetooth != null) {
                        bluetooth.connect(); deadline.remaining()
                        return@submit StreamChannel(bluetooth.inputStream, bluetooth.outputStream, { bluetooth.close() })
                    }
                    val address = InetSocketAddress(selectedHost, selectedPort)
                    deadline.remaining()
                    socket.connect(address, deadline.remaining())
                    StreamChannel(socket)
                } catch (error: Throwable) { socket.close(); runCatching { bluetooth?.close() }; throw error }
            }
            val channel = try { future.get(deadline.remaining().toLong(), TimeUnit.MILLISECONDS) }
                catch (error: Exception) { socket.close(); runCatching { bluetooth?.close() }; future.cancel(true); throw error }
            candidate = ReaderClient(channel, { status -> synchronized(lock) {
                if (generation == token && view.value.device != null) view.value = view.value.copy(status = status)
            } }, { error -> synchronized(lock) {
                if (generation == token) { client = null; view.value = View(error = error?.let(::failure)); log("Disconnected: ${if (error == null) "requested" else error.message ?: error.javaClass.simpleName}") }
            } }, ::log)
            candidate.handshake(deadline)
            synchronized(lock) {
                deadline.remaining()
                if (token != generation || !candidate.isOpen()) throw ReaderFailure(5, "Connection cancelled")
                client = candidate; view.value = View(device, candidate.status)
            }
            log("Connected ${device.detail}")
        } catch (error: Throwable) { candidate?.close(); throw error }
        finally { connectGate.release() }
    }
    fun disconnect() {
        val old = synchronized(lock) { generation++; client.also { client = null; view.value = View() } }
        old?.close(); log("Disconnected")
    }
    private fun connection(id: String?, slot: Int): ReaderClient = synchronized(lock) {
        if (slot != 0 || view.value.device?.deviceId != id || client == null) throw ReaderFailure(8, "Reader not connected")
        client!!
    }
    fun select(id: String?, slot: Int) { connection(id, slot).select() }
    fun unselect(id: String?, slot: Int) { connection(id, slot).unselect() }
    fun transmit(id: String?, slot: Int, command: ByteArray?, timeout: Int): ByteArray {
        val deadline = Deadline(timeout)
        if (command == null || !Apdu.valid(command)) throw ReaderFailure(4, "Malformed APDU")
        return connection(id, slot).transmit(command, deadline)
    }
    fun reader(): ReaderInfo? = view.value.device?.let { ReaderInfo(it.deviceId, 0, "Android Native NFC", "HceBox", "Android ISO-DEP", null) }
    fun readerStatus(): ReaderStatus? = view.value.let { state -> state.device?.let { ReaderStatus(it.deviceId, 0, state.status.present, state.status.selected, state.error) } }
    fun failure(error: Throwable): DriverError {
        val cause = if (error is ExecutionException) error.cause ?: error else error
        val code = when (cause) {
            is ReaderFailure -> when (cause.code) {
                1 -> CardReaderErrorCode.CARD_ABSENT; 2 -> CardReaderErrorCode.CARD_REMOVED
                3 -> CardReaderErrorCode.TIMEOUT; 4 -> CardReaderErrorCode.UNSUPPORTED_APDU
                6 -> CardReaderErrorCode.BUSY; 7 -> CardReaderErrorCode.READER_NOT_SELECTED
                8 -> CardReaderErrorCode.SETUP_REQUIRED; else -> CardReaderErrorCode.TRANSPORT
            }
            is TimeoutException, is java.net.SocketTimeoutException -> CardReaderErrorCode.TIMEOUT
            is SecurityException -> CardReaderErrorCode.SETUP_REQUIRED
            is IllegalArgumentException -> CardReaderErrorCode.DEVICE_NOT_FOUND
            else -> CardReaderErrorCode.TRANSPORT
        }
        log("Operation failed code=$code reason=${cause.message}")
        return DriverError(code, cause.message)
    }
}
