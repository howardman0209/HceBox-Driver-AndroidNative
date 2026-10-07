package com.hcebox.driver.androidnative.connection.tcp

import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ConnectAttempt
import com.hcebox.driver.androidnative.connection.ReaderConnector
import com.hcebox.reader.protocol.channel.MessageChannel
import com.hcebox.reader.protocol.channel.StreamChannel
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.Hello
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Manual endpoint plus NSD services; NSD entries are re-resolved within the connect deadline. */
class TcpConnector(
    private val scanner: TcpScanner,
    private val manualEndpoint: () -> TcpEndpoint?,
) : ReaderConnector {
    override fun devices(): List<DeviceInfo> =
        (listOfNotNull(manualEndpoint()?.device) + scanner.endpoints().map { it.device })
            .distinctBy { it.deviceId }

    override fun open(
        device: DeviceInfo,
        deadline: Deadline,
        attempt: ConnectAttempt,
    ): MessageChannel {
        val endpoint =
            if (device.deviceId.startsWith("TCP:NSD:")) scanner.refresh(device.deviceId, deadline)
            else
                manualEndpoint()?.takeIf { it.device.deviceId == device.deviceId }
                    ?: throw IllegalArgumentException("TCP endpoint not found")
        var lastError: IOException? = null
        for (host in endpoint.hosts) for (address in InetAddress.getAllByName(host)) {
            deadline.remaining()
            val connection = endpoint.network?.socketFactory?.createSocket() ?: Socket()
            attempt.track(connection)
            try {
                if (Thread.currentThread().isInterrupted)
                    throw InterruptedIOException("Connection cancelled")
                connection.connect(InetSocketAddress(address, endpoint.port), deadline.remaining())
                return StreamChannel(connection)
            } catch (error: IOException) {
                connection.close()
                lastError = error
            }
        }
        throw lastError ?: IOException("TCP endpoint has no usable address")
    }

    override fun verify(device: DeviceInfo, hello: Hello?) {
        if (!device.deviceId.startsWith("TCP:NSD:")) return
        check(hello?.installationId == device.deviceId.removePrefix("TCP:NSD:")) {
            "Reader identity differs from NSD announcement"
        }
    }
}
