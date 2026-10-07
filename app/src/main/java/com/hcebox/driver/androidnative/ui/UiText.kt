package com.hcebox.driver.androidnative.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.hcebox.cardreader.api.CardReaderErrorCode
import com.hcebox.cardreader.api.DriverError
import com.hcebox.driver.androidnative.*
import com.hcebox.driver.androidnative.connection.ConnectionMode

internal fun transportLabel(mode: ConnectionMode) =
    when (mode) {
        ConnectionMode.TCP -> "Local network"
        ConnectionMode.CLASSIC -> "Bluetooth Classic"
        ConnectionMode.BLE -> "Bluetooth LE"
    }

internal fun friendlyError(error: DriverError) =
    when (error.code) {
        CardReaderErrorCode.SETUP_REQUIRED ->
            "Access is required. Review permissions and enable the selected connection method."
        CardReaderErrorCode.TIMEOUT ->
            "The reader did not respond in time. Keep it running and try again."
        CardReaderErrorCode.DEVICE_NOT_FOUND ->
            "Reader unavailable. Search again or check the manual address and port."
        CardReaderErrorCode.BUSY ->
            "The reader is busy. Disconnect the other connection and try again."
        else -> "Reader could not be reached. Check the connection and try again."
    }
