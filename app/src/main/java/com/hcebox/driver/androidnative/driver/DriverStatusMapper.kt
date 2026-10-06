package com.hcebox.driver.androidnative.driver

import com.hcebox.cardreader.api.CardReaderErrorCode
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.cardreader.api.DriverError
import com.hcebox.cardreader.api.ReaderInfo
import com.hcebox.cardreader.api.ReaderStatus
import com.hcebox.reader.protocol.ReaderFailure
import com.hcebox.reader.protocol.Status
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** Converts wire/transport failures to the existing AIDL error contract without Android state. */
internal object DriverStatusMapper {
    fun reader(device: DeviceInfo): ReaderInfo =
        ReaderInfo(device.deviceId, 0, "Android Native NFC", "HceBox", "Android ISO-DEP", null)

    fun readerStatus(device: DeviceInfo, status: Status): ReaderStatus =
        ReaderStatus(
            device.deviceId,
            0,
            status.present,
            status.selected,
            status.lastError.takeIf { it != 0 }?.let(::cardError),
        )

    fun cardError(code: Int) =
        DriverError(
            when (code) {
                1 -> CardReaderErrorCode.CARD_ABSENT
                2 -> CardReaderErrorCode.CARD_REMOVED
                3 -> CardReaderErrorCode.TIMEOUT
                4 -> CardReaderErrorCode.UNSUPPORTED_APDU
                6 -> CardReaderErrorCode.BUSY
                7 -> CardReaderErrorCode.READER_NOT_SELECTED
                8 -> CardReaderErrorCode.SETUP_REQUIRED
                else -> CardReaderErrorCode.TRANSPORT
            },
            if (code == 2) "Card removed" else "Reader error code=$code",
        )

    fun failure(error: Throwable): DriverError {
        val cause = if (error is ExecutionException) error.cause ?: error else error
        val code =
            when (cause) {
                is ReaderFailure ->
                    when (cause.code) {
                        1 -> CardReaderErrorCode.CARD_ABSENT
                        2 -> CardReaderErrorCode.CARD_REMOVED
                        3 -> CardReaderErrorCode.TIMEOUT
                        4 -> CardReaderErrorCode.UNSUPPORTED_APDU
                        6 -> CardReaderErrorCode.BUSY
                        7 -> CardReaderErrorCode.READER_NOT_SELECTED
                        8 -> CardReaderErrorCode.SETUP_REQUIRED
                        else -> CardReaderErrorCode.TRANSPORT
                    }

                is TimeoutException,
                is java.net.SocketTimeoutException -> CardReaderErrorCode.TIMEOUT
                is SecurityException -> CardReaderErrorCode.SETUP_REQUIRED
                is IllegalArgumentException -> CardReaderErrorCode.DEVICE_NOT_FOUND
                else -> CardReaderErrorCode.TRANSPORT
            }
        return DriverError(code, cause.message)
    }
}
