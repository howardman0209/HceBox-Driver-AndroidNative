package com.hcebox.driver.androidnative.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import com.hcebox.driver.androidnative.*
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.ui.transportLabel

/** Settings presentation; actions are coordinated by the app host. */
@Composable
internal fun DriverSettingsScreen(
    mode: ConnectionMode,
    onSetup: () -> Unit,
    remoteOrigin: String,
    onOrigin: (String) -> Unit,
    canSave: Boolean,
    onSaveOrigin: () -> Unit,
) {
    val context = LocalContext.current
    if (mode == ConnectionMode.REMOTE) {
        Text("Remote server", style = MaterialTheme.typography.titleMedium)
        Text("Use the same HTTPS server as the Reader. No account or pairing code is needed.")
        OutlinedTextField(remoteOrigin, onOrigin, enabled = canSave, singleLine = true,
            label = { Text("HTTPS server origin") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onSaveOrigin, enabled = canSave && remoteOrigin.isNotBlank()) { Text("Save server") }
        HorizontalDivider()
    }
    Text("Connection setup", style = MaterialTheme.typography.titleMedium)
    Text("Permissions apply to the selected connection method: ${transportLabel(mode)}.")
    OutlinedButton(onClick = { onSetup() }) {
        Text("Review permissions")
    }
    OutlinedButton(
        onClick = {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    ) {
        Text("Bluetooth pairing")
    }
    HorizontalDivider()
    Text("Use with HceBox", style = MaterialTheme.typography.titleMedium)
    Text(
        "In HceBox, open Settings → Card Transport → Proxy. Scan and choose Android Native NFC Reader."
    )
}
