package com.hcebox.driver.androidnative.connection

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.cardreader.api.DriverError
import com.hcebox.cardreader.api.ReaderInfo
import com.hcebox.cardreader.api.ReaderStatus
import com.hcebox.driver.androidnative.connection.ble.BleConnector
import com.hcebox.driver.androidnative.connection.classic.ClassicConnector
import com.hcebox.driver.androidnative.connection.tcp.TcpConnector
import com.hcebox.driver.androidnative.connection.tcp.TcpEndpoint
import com.hcebox.driver.androidnative.driver.DriverStatusMapper
import com.hcebox.driver.androidnative.setup.missingPermissions
import com.hcebox.reader.protocol.codec.Apdu
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.ReaderFailure
import com.hcebox.reader.protocol.model.Status
import com.hcebox.reader.protocol.session.ReaderClient
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    // Application-owned controller work must outlive the UI; Main preserves queued scan commands.
    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val discovery =
        ReaderDiscovery(
            this.context,
            mode = { mode },
            permissions = { missingPermissions(this.context, mode) },
            listDevices = ::devices,
            log = ::log,
            scope = discoveryScope,
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

    val mode: ConnectionMode
        get() {
            val saved = prefs.getString("mode", null)
            // Missing or unknown values fall back to the TCP default.
            return ConnectionMode.entries.firstOrNull { it.name == saved } ?: ConnectionMode.TCP
        }

    fun setMode(value: ConnectionMode) {
        check(view.value.device == null)
        disconnect()
        discovery.reset()
        prefs.edit { putString("mode", value.name) }
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

    /** Picks the link implementation for a mode; the session lifecycle stays in this class. */
    private fun connector(mode: ConnectionMode): ReaderConnector =
        when (mode) {
            ConnectionMode.TCP -> TcpConnector(discovery.tcp, ::manualEndpoint)
            ConnectionMode.CLASSIC -> ClassicConnector(context)
            ConnectionMode.BLE -> BleConnector(context, { discovery.devices.value }, ::log)
        }

    fun devices(): List<DeviceInfo> = connector(mode).devices()

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
            // Read the mode once so a concurrent switch cannot mix transports mid-connect.
            val selectedMode = mode
            val connector = connector(selectedMode)
            val device =
                connector.devices().firstOrNull { it.deviceId == id }
                    ?: throw IllegalArgumentException("Device not found")
            if (missingPermissions(context, selectedMode).isNotEmpty())
                throw SecurityException("Transport permissions required")
            log("Connecting ${device.detail}")
            val channel = openWithin(workers, deadline, ::log) { connector.open(device, deadline, it) }
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
            connector.verify(device, candidate.hello)
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
