package com.hcebox.driver.androidnative.connection.remote

import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ConnectAttempt
import com.hcebox.driver.androidnative.connection.ReaderConnector
import com.hcebox.reader.protocol.channel.MessageChannel
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.ReaderFailure
import com.hcebox.remote.client.RemoteClient
import com.hcebox.remote.client.RemoteServerOrigin
import com.hcebox.remote.client.RemoteSocket
import java.io.Closeable
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

/** Opens the observed generation; a failed/stale match never selects another Reader. */
class RemoteConnector(private val scanner: RemoteScanner) : ReaderConnector {
    override fun devices() = scanner.endpoints().map { it.device }

    override fun open(device: DeviceInfo, deadline: Deadline, attempt: ConnectAttempt): MessageChannel {
        val endpoint = scanner.endpoints().firstOrNull { it.device.deviceId == device.deviceId }
            ?: throw ReaderFailure(5, "Remote Reader is no longer available; search again")
        val client = RemoteClient(RemoteServerOrigin(endpoint.origin))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lock = Any()
        var closed = false
        var socket: RemoteSocket? = null
        val owner = Closeable {
            val old = synchronized(lock) { closed = true; socket }
            scope.cancel()
            old?.close()
            client.close()
        }
        attempt.track(owner)
        val pending = scope.async {
            val opened = client.connect(endpoint.readerId)
            synchronized(lock) {
                if (closed) { opened.close(); throw ReaderFailure(5, "Remote connection cancelled") }
                socket = opened
            }
            opened
        }
        try {
            val opened = runBlocking { withTimeout(deadline.remaining().milliseconds) { pending.await() } }
            deadline.remaining()
            return RemoteMessageChannel(opened, owner::close)
        } catch (error: Exception) {
            owner.close()
            throw remoteFailure(error)
        }
    }
}
