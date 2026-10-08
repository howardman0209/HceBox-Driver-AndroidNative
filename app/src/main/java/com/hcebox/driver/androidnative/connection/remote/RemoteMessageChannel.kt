package com.hcebox.driver.androidnative.connection.remote

import com.hcebox.remote.client.RemoteSocket
import com.hcebox.reader.protocol.channel.MessageChannel
import com.hcebox.reader.protocol.codec.Codec
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.Frame
import com.hcebox.reader.protocol.model.ReaderFailure
import com.hcebox.reader.protocol.model.Type
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

/** Worker-only v2 adapter; cancellation releases the actual Remote socket and pending calls. */
internal class RemoteMessageChannel(
    private val socket: RemoteSocket,
    private val closeOwner: () -> Unit,
    private val onHello: (() -> Unit)? = null,
) : MessageChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean()

    override fun receive(deadline: Deadline?): Frame = call(deadline) {
        val received = socket.receiveBinaryFrame()
        Codec.decode(received.bytes).copy(receivedAtNanos = received.receivedAtNanos)
    }

    override fun send(frame: Frame, deadline: Deadline) {
        call(deadline) {
            socket.sendBinary(Codec.encode(frame))
            if (frame.type == Type.HELLO_OK && onHello != null) {
                socket.sessionReady()
                onHello.invoke()
            }
        }
    }

    private fun <T> call(deadline: Deadline?, action: suspend () -> T): T {
        if (closed.get()) throw ReaderFailure(5, "Remote channel closed")
        val pending = scope.async { action() }
        try {
            return runBlocking {
                if (deadline == null) pending.await()
                else withTimeout(deadline.remaining().milliseconds) { pending.await() }
            }
        } catch (error: TimeoutCancellationException) {
            close()
            throw ReaderFailure(3, "Remote operation timeout")
        } catch (error: CancellationException) {
            close()
            throw ReaderFailure(5, "Remote channel closed")
        } finally { if (!pending.isCompleted) pending.cancel() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        runCatching { socket.close() }
        runCatching { closeOwner() }
    }
}
