package com.hcebox.driver.androidnative.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hcebox.driver.androidnative.*

/** Manual connection presentation; actions are coordinated by the app host. */
@Composable
internal fun ManualConnectionScreen(
    host: String,
    port: String,
    busy: Boolean,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Text("Enter the address shown in the reader's connection information.")
    OutlinedTextField(
        host,
        onHost,
        label = { Text("Hostname or IP address") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        port,
        onPort,
        label = { Text("Port") },
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        enabled = !busy && host.isNotBlank() && port.toIntOrNull() in 1..65535,
        onClick = { onConnect() },
    ) {
        Text(if (busy) "Connecting…" else "Connect")
    }
}
