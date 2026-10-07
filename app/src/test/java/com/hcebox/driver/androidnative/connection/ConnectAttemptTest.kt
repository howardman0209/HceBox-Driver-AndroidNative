package com.hcebox.driver.androidnative.connection

import java.io.Closeable
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class ConnectAttemptTest {
    private class Resource : Closeable {
        var closes = 0

        override fun close() {
            closes++
        }
    }

    @Test
    fun abortClosesTrackedResource() {
        val attempt = ConnectAttempt()
        val socket = Resource()
        attempt.track(socket)
        assertEquals(0, socket.closes)
        attempt.abort()
        assertEquals(1, socket.closes)
    }

    @Test
    fun resourceTrackedAfterAbortIsClosedImmediately() {
        // A worker may create its socket after the caller already timed out.
        val attempt = ConnectAttempt()
        attempt.abort()
        val late = Resource()
        assertThrows(InterruptedIOException::class.java) { attempt.track(late) }
        assertEquals(1, late.closes)
    }
}
