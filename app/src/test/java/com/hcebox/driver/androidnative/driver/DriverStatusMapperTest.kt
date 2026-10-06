package com.hcebox.driver.androidnative.driver

import com.hcebox.cardreader.api.CardReaderErrorCode
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.reader.protocol.ReaderFailure
import com.hcebox.reader.protocol.Status
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import org.junit.Assert.*
import org.junit.Test

class DriverStatusMapperTest {
    @Test
    fun removalPreservesSelectionAndFreshCardClearsError() {
        val device = DeviceInfo("reader", "Test reader", null)
        val removed =
            DriverStatusMapper.readerStatus(device, Status(selected = true, lastError = 2))
        assertFalse(removed.isCardPresent)
        assertTrue(removed.isSelected)
        assertEquals(CardReaderErrorCode.CARD_REMOVED, removed.lastError?.code)
        val fresh =
            DriverStatusMapper.readerStatus(
                device,
                Status(session = 3, selected = true, present = true),
            )
        assertTrue(fresh.isCardPresent)
        assertNull(fresh.lastError)
    }

    @Test
    fun wrappedTimeoutIsDistinctFromRemovalAndTransportFailure() {
        val timeout =
            DriverStatusMapper.failure(ExecutionException(SocketTimeoutException("Expired")))
        assertEquals(CardReaderErrorCode.TIMEOUT, timeout.code)
        assertEquals("Expired", timeout.message)
        assertEquals(
            CardReaderErrorCode.CARD_REMOVED,
            DriverStatusMapper.failure(ReaderFailure(2, "Removed")).code,
        )
        assertEquals(
            CardReaderErrorCode.TRANSPORT,
            DriverStatusMapper.failure(java.io.IOException("Link lost")).code,
        )
    }
}
