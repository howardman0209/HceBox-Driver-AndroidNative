package com.hcebox.driver.androidnative.connection.remote

import com.hcebox.reader.protocol.codec.Codec
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.Frame
import com.hcebox.reader.protocol.model.Status
import com.hcebox.reader.protocol.model.Type
import com.hcebox.reader.protocol.session.ReaderClient
import com.hcebox.remote.client.RemoteClient
import com.hcebox.remote.client.RemoteServerOrigin
import com.hcebox.remote.contract.Control
import com.hcebox.remote.contract.RemoteContract
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.close
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class RemoteChannelTest {
    @Test fun driverHandshakeSelectAndApduPreserveBytes() = scenario(false)
    @Test fun disconnectAfterDispatchDoesNotReplayApdu() = scenario(true)

    private fun scenario(dropResponse: Boolean) = testApplication {
        val id = "00000000-0000-4000-8000-000000000001"
        val generation = "00000000-0000-4000-8000-000000000002"
        val command = byteArrayOf(0, 0xa4.toByte(), 4, 0)
        val completed = CompletableDeferred<Unit>()
        var calls = 0
        application {
            this.install(ServerWebSockets)
            routing {
                webSocket("/remote/v1/driver", protocol = RemoteContract.SUBPROTOCOL) {
                    try {
                        send(WsFrame.Text(RemoteContract.encode(Control.Matched(id, generation))))
                        val hello = Codec.decode((incoming.receive() as WsFrame.Binary).data)
                        assertEquals(Type.HELLO, hello.type)
                        val status = Status(session = 99, present = true, maxCommand = 261)
                        send(WsFrame.Binary(true, Codec.encode(Frame(Type.HELLO_OK, hello.requestId, 99,
                            Codec.helloPayload("reader-installation", "Reader", status)))))
                        val select = Codec.decode((incoming.receive() as WsFrame.Binary).data)
                        assertEquals(Type.SELECT, select.type)
                        send(WsFrame.Binary(true, Codec.encode(Frame(Type.SELECT_OK, select.requestId, 99,
                            Codec.statusPayload(status.copy(selected = true))))))
                        val apdu = Codec.decode((incoming.receive() as WsFrame.Binary).data)
                        assertEquals(Type.APDU, apdu.type)
                        assertEquals(99L, apdu.session)
                        assertArrayEquals(command, apdu.payload.copyOfRange(4, apdu.payload.size))
                        calls++
                        if (!dropResponse) send(WsFrame.Binary(true, Codec.encode(Frame(Type.APDU_OK, apdu.requestId, 99,
                            byteArrayOf(0x90.toByte(), 0)))))
                        completed.complete(Unit)
                        if (!dropResponse) incoming.receiveCatching()
                    } catch (error: Throwable) { completed.completeExceptionally(error); throw error }
                    finally { close() }
                }
            }
        }
        val http = createClient { install(WebSockets) }
        val remote = RemoteClient(RemoteServerOrigin("https://localhost"), http)
        val socket = remote.connect(id)
        val reader = ReaderClient(RemoteMessageChannel(socket, remote::close))
        try {
            withContext(Dispatchers.IO) {
                reader.handshake(Deadline(5000))
                reader.select()
                if (dropResponse) assertNotNull(runCatching { reader.transmit(command, Deadline(2000)) }.exceptionOrNull())
                else assertArrayEquals(byteArrayOf(0x90.toByte(),0), reader.transmit(command, Deadline(2000)))
            }
            withTimeout(3.seconds) { completed.await() }
            assertEquals(1, calls)
            if (dropResponse) assertFalse(reader.isOpen())
        } finally { reader.close(); remote.close() }
    }
}
