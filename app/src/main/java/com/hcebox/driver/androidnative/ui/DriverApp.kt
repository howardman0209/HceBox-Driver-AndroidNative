package com.hcebox.driver.androidnative.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.hcebox.driver.androidnative.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcebox.cardreader.api.CardReaderErrorCode
import com.hcebox.cardreader.api.DriverError
import com.hcebox.driver.androidnative.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small single-Activity flow; backing out of details never disconnects a reader. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverApp(controller: NativeController) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by controller.view.collectAsStateWithLifecycle()
    val devices by controller.discovery.devices.collectAsStateWithLifecycle()
    val notes by controller.notes.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("Readers") }
    val scroll = remember(page) { ScrollState(0) }
    var mode by remember { mutableStateOf(controller.mode) }
    var permitted by remember { mutableStateOf(missingPermissions(context).isEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<DriverError?>(null) }
    var lastId by rememberSaveable { mutableStateOf<String?>(null) }
    var host by rememberSaveable { mutableStateOf(controller.host) }
    var port by rememberSaveable { mutableStateOf(controller.port.toString()) }
    val owner = remember { Any() }
    val setup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permitted = missingPermissions(context).isEmpty(); refresh++
    }
    DisposableEffect(lifecycle, mode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mode = controller.mode; permitted = missingPermissions(context).isEmpty(); refresh++
            }
            if (event == Lifecycle.Event.ON_STOP) controller.discovery.stop(owner)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.discovery.stop(owner) }
    }
    DisposableEffect(page, mode, permitted, refresh) {
        if (page == "Readers" && permitted) {
            scanning = mode != "CLASSIC"
            val scanMode = mode
            controller.discovery.start(owner) {
                // A transport switch retires the previous scan; it is not a connection failure.
                if (controller.mode == scanMode) { error = controller.failure(it); scanning = false }
            }
        }
        onDispose { controller.discovery.stop(owner); scanning = false }
    }
    LaunchedEffect(state.device) {
        state.device?.let { lastId = it.deviceId }
    }
    LaunchedEffect(page) { controller.log("UI page opened: $page") }
    fun connect(id: String?, manual: Boolean = false) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = if (manual) {
                        controller.configure(host, port.toIntOrNull() ?: throw IllegalArgumentException("Invalid port"))
                        controller.devices().first { !it.deviceId.startsWith("TCP:NSD:") }.deviceId
                    } else id
                    controller.connect(target, 5000)
                }
                page = "Reader details"
            } catch (failure: Exception) { error = controller.failure(failure) }
            finally { busy = false }
        }
    }
    fun back() { page = "Readers" }
    BackHandler(enabled = page != "Readers") { back() }
    Scaffold(modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = { Text(if (page == "Readers") "Native Driver" else page) },
            navigationIcon = { if (page in listOf("Reader details", "Manual connection")) IconButton(onClick = { back() }) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") } })
    }, bottomBar = {
        NavigationBar {
            listOf("Readers", "Settings", "Diagnostics").forEachIndexed { index, destination ->
                NavigationBarItem(selected = if (index == 0) page in listOf("Readers", "Reader details", "Manual connection") else page == destination,
                    enabled = !busy, onClick = { page = destination }, label = { Text(destination) },
                    icon = { Icon(painterResource(listOf(R.drawable.ic_readers, R.drawable.ic_settings, R.drawable.ic_diagnostics)[index]), contentDescription = null) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val visibleError = error ?: state.error
            if (visibleError != null && page != "Diagnostics") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(friendlyError(visibleError), Modifier.padding(16.dp))
                }
            }
            when (page) {
                "Readers" -> {
                    Text("Connect to another Android device running Native NFC Reader.", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("TCP", "CLASSIC", "BLE").forEach { value ->
                            FilterChip(selected = mode == value, enabled = !busy && state.device == null,
                                onClick = { controller.setMode(value); mode = value; error = null; permitted = missingPermissions(context).isEmpty() },
                                label = { Text(if (value == "CLASSIC") "Classic" else value) })
                        }
                    }
                    if (!permitted) {
                        Text("Allow ${if (mode == "TCP") "local network" else "Bluetooth"} access to find and connect readers.")
                        Button(onClick = { setup.launch(Intent(context, SetupActivity::class.java)) }) { Text("Allow access") }
                    }
                    state.device?.let { connected ->
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        ReaderRow(connected.displayName, "${transportLabel(mode)} · Open reader", !busy) { page = "Reader details" }
                    }
                    Text(if (mode == "CLASSIC") "Paired readers" else "Available readers", style = MaterialTheme.typography.titleMedium)
                    if (scanning) Text("Searching for readers…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    devices.filter { it.deviceId != state.device?.deviceId }.forEach { device ->
                        ReaderRow(device.displayName, transportLabel(mode), !busy && state.device == null && permitted) { lastId = device.deviceId; connect(device.deviceId) }
                    }
                    if (devices.isEmpty()) Text(if (mode == "TCP") "Start the reader and connect both devices to the same local network."
                        else if (mode == "BLE") "Start Bluetooth LE on the reader and keep it nearby." else "Pair the reader in Bluetooth settings first.")
                    if (busy) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Connecting…") }
                    TextButton(enabled = !busy && permitted, onClick = { error = null; refresh++ }) { Text("Search again") }
                    if (mode == "TCP") OutlinedButton(enabled = !busy && state.device == null, onClick = { page = "Manual connection" }) { Text("Manual connection") }
                    if (mode == "CLASSIC") OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Pair a reader") }
                }
                "Manual connection" -> {
                    Text("Enter the address shown in the reader's connection information.")
                    OutlinedTextField(host, { host = it }, label = { Text("Hostname or IP address") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !busy && host.isNotBlank() && port.toIntOrNull() in 1..65535, onClick = { connect(null, true) }) { Text(if (busy) "Connecting…" else "Connect") }
                }
                "Reader details" -> {
                    Text(state.device?.displayName ?: "Reader disconnected", style = MaterialTheme.typography.headlineSmall)
                    StatusCard("Connection", if (state.device != null) "Connected via ${transportLabel(mode)}" else "Disconnected")
                    StatusCard("Card", if (state.device == null) "Unavailable" else if (state.status.present) "Card detected"
                        else if (state.status.lastError == 2) "Card removed · Place the card again" else "Waiting for a card")
                    StatusCard("Reader use", if (state.device == null) "Unavailable" else if (state.status.selected) "In use" else "Available")
                    if (state.device != null) Button(enabled = !busy, onClick = { controller.disconnect(); error = null; page = "Readers" }) { Text("Disconnect") }
                    else Button(enabled = !busy && lastId != null, onClick = { connect(lastId) }) { Text(if (busy) "Connecting…" else "Reconnect") }
                }
                "Settings" -> {
                    Text("Connection setup", style = MaterialTheme.typography.titleMedium)
                    Text("Permissions apply to the selected connection method: ${transportLabel(mode)}.")
                    OutlinedButton(onClick = { setup.launch(Intent(context, SetupActivity::class.java)) }) { Text("Review permissions") }
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth pairing") }
                    HorizontalDivider()
                    Text("Use with HceBox", style = MaterialTheme.typography.titleMedium)
                    Text("In HceBox, open Settings → Card Transport → Proxy. Scan and choose Android Native NFC Reader.")
                }
                "Diagnostics" -> {
                    Text("Connection information", style = MaterialTheme.typography.titleMedium)
                    Text("${state.device?.detail ?: "Disconnected"}\nSession: ${state.status.session}\nSelected: ${state.status.selected}\nMaximum command: ${state.status.maxCommand}\nExtended APDU: ${state.status.extended}\nError: ${visibleError?.code ?: state.status.lastError}")
                    Row {
                        TextButton(onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Driver diagnostics", notes)) }) { Text("Copy log") }
                        TextButton(onClick = { controller.notes.value = "" }) { Text("Clear log") }
                    }
                    Text(notes.ifBlank { "No log entries" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReaderRow(name: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    } }
}

private fun transportLabel(mode: String) = when (mode) { "TCP" -> "Local network"; "CLASSIC" -> "Bluetooth Classic"; else -> "Bluetooth LE" }
private fun friendlyError(error: DriverError) = when (error.code) {
    CardReaderErrorCode.SETUP_REQUIRED -> "Access is required. Review permissions and enable the selected connection method."
    CardReaderErrorCode.TIMEOUT -> "The reader did not respond in time. Keep it running and try again."
    CardReaderErrorCode.DEVICE_NOT_FOUND -> "Reader unavailable. Search again or check the manual address and port."
    CardReaderErrorCode.BUSY -> "The reader is busy. Disconnect the other connection and try again."
    else -> "Reader could not be reached. Check the connection and try again."
}
