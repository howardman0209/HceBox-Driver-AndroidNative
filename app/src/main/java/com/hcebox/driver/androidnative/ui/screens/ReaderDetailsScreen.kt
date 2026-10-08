package com.hcebox.driver.androidnative.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.hcebox.driver.androidnative.*
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.connection.NativeController
import com.hcebox.driver.androidnative.ui.components.StatusCard
import com.hcebox.driver.androidnative.ui.transportLabel

/** Reader details presentation; actions are coordinated by the app host. */
@Composable
internal fun ReaderDetailsScreen(
    state: NativeController.View,
    mode: ConnectionMode,
    busy: Boolean,
    canReconnect: Boolean,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    Text(
        state.device?.displayName ?: "Reader disconnected",
        style = MaterialTheme.typography.headlineSmall,
    )
    StatusCard(
        "Connection",
        if (state.device != null) "Connected via ${transportLabel(mode)}" else "Disconnected",
    )
    StatusCard(
        "Card",
        if (state.device == null) "Unavailable"
        else if (state.status.present) "Card detected"
        else if (state.status.lastError == 2) "Card removed · Place the card again"
        else "Waiting for a card",
    )
    StatusCard(
        "Reader use",
        if (state.device == null) "Unavailable"
        else if (state.status.selected) "In use" else "Available",
    )
    if (state.device != null)
        Button(
            enabled = !busy,
            onClick = {
                onDisconnect()
            },
        ) {
            Text("Disconnect")
        }
    else
        Button(enabled = !busy && canReconnect, onClick = { onReconnect() }) {
            Text(if (busy) "Connecting…" else if (mode == ConnectionMode.REMOTE) "Find an available Reader" else "Reconnect")
        }
}
