package com.hcebox.driver.androidnative.connection

import com.hcebox.reader.protocol.channel.MessageChannel
import com.hcebox.reader.protocol.core.Deadline
import com.hcebox.reader.protocol.model.Frame
import com.hcebox.reader.protocol.model.ReaderFailure
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OpenWithinTest {
    /** Runs each task inside submit(), so the worker always finishes before the caller waits. */
    private object Inline : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()

        override fun shutdown() {}

        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

        override fun isShutdown() = false

        override fun isTerminated() = false

        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class Channel : MessageChannel {
        var closed = false

        override fun receive(deadline: Deadline?): Frame = error("unused")

        override fun send(frame: Frame, deadline: Deadline) {
            error("unused")
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun linkReadyWithinDeadlineStaysOpen() {
        val channel = Channel()
        assertSame(channel, openWithin(Inline, Deadline(5000), {}) { channel })
        assertFalse(channel.closed)
    }

    @Test
    fun linkReadyAfterDeadlineIsClosed() {
        // The worker succeeds, but the caller's deadline has already expired when it collects.
        val late = Channel()
        val logs = mutableListOf<String>()
        assertThrows(ReaderFailure::class.java) {
            openWithin(Inline, Deadline(1), { logs += it }) {
                Thread.sleep(20)
                late
            }
        }
        assertTrue(late.closed)
        assertEquals(listOf("Closed a link that connected after the deadline"), logs)
    }
}
