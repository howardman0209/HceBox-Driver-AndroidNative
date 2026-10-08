package com.hcebox.driver.androidnative.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.hcebox.driver.androidnative.*
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.ui.transportLabel

/** Settings presentation; actions are coordinated by the app host. */
@Composable
internal fun DriverSettingsScreen(
    mode: ConnectionMode,
    onSetup: () -> Unit,
) {
    val context = LocalContext.current
    if (mode == ConnectionMode.REMOTE) {
        Text("Available Readers can be discovered online. No account or pairing code is needed.")
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
