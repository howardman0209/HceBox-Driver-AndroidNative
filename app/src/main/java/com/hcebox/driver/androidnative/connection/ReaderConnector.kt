package com.hcebox.driver.androidnative.connection

import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.reader.protocol.channel.MessageChannel
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.Hello
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** One connection mode: lists candidate devices and opens a framed link. Never owns the session. */
interface ReaderConnector {
    /** Candidates for this mode; throws when the transport is unusable. */
    fun devices(): List<DeviceInfo>

    /** Blocks on a worker thread; sockets that ignore interrupts must be tracked by [attempt]. */
    fun open(device: DeviceInfo, deadline: Deadline, attempt: ConnectAttempt): MessageChannel

    /** Rejects a reader whose HELLO contradicts the identity it was discovered with. */
    fun verify(device: DeviceInfo, hello: Hello?) {}
}

/** Lets a caller-side timeout close the socket a worker is blocked on. */
class ConnectAttempt {
    private var aborted = false
    private var resource: Closeable? = null

    /** Registers the blocking resource; closes it at once when the attempt was already aborted. */
    @Synchronized
    fun track(resource: Closeable) {
        if (aborted) {
            runCatching { resource.close() }
            throw InterruptedIOException("Connection cancelled")
        }
        this.resource = resource
    }

    @Synchronized
    fun abort() {
        aborted = true
        resource?.let { runCatching { it.close() } }
    }
}

/**
 * Runs [open] on [workers] within [deadline]. Failure closes the tracked socket, and a link that
 * finishes connecting after the caller gave up is closed instead of leaking.
 */
internal fun openWithin(
    workers: ExecutorService,
    deadline: Deadline,
    log: (String) -> Unit,
    open: (ConnectAttempt) -> MessageChannel,
): MessageChannel {
    val attempt = ConnectAttempt()
    val future =
        workers.submit<MessageChannel> {
            try {
                open(attempt)
            } catch (error: Throwable) {
                attempt.abort()
                throw error
            }
        }
    return try {
        future.get(deadline.remaining().toLong(), TimeUnit.MILLISECONDS)
    } catch (error: Exception) {
        attempt.abort()
        // cancel() fails once the worker has finished, so its result may be an open channel.
        if (!future.cancel(true))
            runCatching { future.get() }
                .getOrNull()
                ?.let { late ->
                    runCatching { late.close() }
                    log("Closed a link that connected after the deadline")
                }
        throw error
    }
}
