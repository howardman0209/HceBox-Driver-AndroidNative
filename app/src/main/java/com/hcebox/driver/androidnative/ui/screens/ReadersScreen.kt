package com.hcebox.driver.androidnative.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hcebox.driver.androidnative.*
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.connection.NativeController
import com.hcebox.driver.androidnative.ui.components.ReaderRow
import com.hcebox.driver.androidnative.ui.transportLabel

/** Readers presentation; actions are coordinated by the app host. */
@Composable
internal fun ReadersScreen(
    mode: ConnectionMode,
    permitted: Boolean,
    state: NativeController.View,
    devices: List<com.hcebox.cardreader.api.DeviceInfo>,
    scanning: Boolean,
    busy: Boolean,
    onMode: (ConnectionMode) -> Unit,
    onSetup: () -> Unit,
    onDetails: () -> Unit,
    onConnect: (String) -> Unit,
    onRescan: () -> Unit,
    onManual: () -> Unit,
) {
    val context = LocalContext.current
    Text(
        "Connect to another Android device running Native NFC Reader.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionMode.entries.forEach { value ->
            FilterChip(
                selected = mode == value,
                enabled = !busy && state.device == null,
                onClick = {
                    onMode(value)
                },
                label = { Text(if (value == ConnectionMode.CLASSIC) "Classic" else value.name) },
            )
        }
    }
    if (!permitted) {
        Text(
            "Allow ${if (mode == ConnectionMode.TCP) "local network" else "Bluetooth"} access to find and connect readers."
        )
        Button(onClick = { onSetup() }) {
            Text("Allow access")
        }
    }
    state.device?.let { connected ->
        Text("Connected", style = MaterialTheme.typography.titleMedium)
        ReaderRow(
            connected.displayName,
            "${transportLabel(mode)} · Open reader",
            !busy,
        ) {
            onDetails()
        }
    }
    Text(
        if (mode == ConnectionMode.CLASSIC) "Paired readers" else "Available readers",
        style = MaterialTheme.typography.titleMedium,
    )
    if (scanning)
        Text(
            "Searching for readers…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    devices
        .filter { it.deviceId != state.device?.deviceId }
        .forEach { device ->
            ReaderRow(
                device.displayName,
                transportLabel(mode),
                !busy && state.device == null && permitted,
            ) {
                onConnect(device.deviceId)
            }
        }
    if (devices.isEmpty())
        Text(
            when (mode) {
                ConnectionMode.TCP ->
                    "Start the reader and connect both devices to the same local network."
                ConnectionMode.BLE -> "Start Bluetooth LE on the reader and keep it nearby."
                ConnectionMode.CLASSIC -> "Pair the reader in Bluetooth settings first."
            }
        )
    if (busy)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("Connecting…")
        }
    TextButton(
        enabled = !busy && permitted,
        onClick = {
            onRescan()
        },
    ) {
        Text("Search again")
    }
    if (mode == ConnectionMode.TCP)
        OutlinedButton(
            enabled = !busy && state.device == null,
            onClick = { onManual() },
        ) {
            Text("Manual connection")
        }
    if (mode == ConnectionMode.CLASSIC)
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        ) {
            Text("Pair a reader")
        }
}
