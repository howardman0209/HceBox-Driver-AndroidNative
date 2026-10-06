package com.hcebox.driver.androidnative.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.hcebox.cardreader.api.DriverError
import com.hcebox.driver.androidnative.*
import com.hcebox.driver.androidnative.connection.NativeController

/** Diagnostics presentation; actions are coordinated by the app host. */
@Composable
internal fun DriverDiagnosticsScreen(
    state: NativeController.View,
    visibleError: DriverError?,
    notes: String,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    Text("Connection information", style = MaterialTheme.typography.titleMedium)
    Text(
        "${state.device?.detail ?: "Disconnected"}\nSession: ${state.status.session}\nSelected: ${state.status.selected}\nMaximum command: ${state.status.maxCommand}\nExtended APDU: ${state.status.extended}\nError: ${visibleError?.code ?: state.status.lastError}"
    )
    Row {
        TextButton(
            onClick = {
                context
                    .getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Driver diagnostics", notes))
            }
        ) {
            Text("Copy log")
        }
        TextButton(onClick = { onClear() }) { Text("Clear log") }
    }
    Text(
        notes.ifBlank { "No log entries" },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}
