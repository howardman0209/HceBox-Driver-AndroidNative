package com.hcebox.driver.androidnative

import android.content.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); NativeController.init(this)
        setContent {
            val state by NativeController.view.collectAsStateWithLifecycle()
            val notes by NativeController.notes.collectAsStateWithLifecycle()
            var mode by remember { mutableStateOf(NativeController.mode) }
            val devices by NativeController.discovery.devices.collectAsStateWithLifecycle()
            var chosen by remember { mutableStateOf<String?>(null) }
            var host by remember { mutableStateOf(NativeController.host) }
            var port by remember { mutableStateOf(NativeController.port.toString()) }
            var busy by remember { mutableStateOf(false) }
            fun work(action: () -> Unit) { busy = true; scope.launch {
                withContext(Dispatchers.IO) { runCatching(action).onFailure { NativeController.failure(it) } }; busy = false
            } }
            MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Android Native Driver", style = MaterialTheme.typography.headlineSmall)
                Row { listOf("TCP", "CLASSIC", "BLE").forEach { choice ->
                    FilterChip(selected = mode == choice, enabled = state.device == null && !busy,
                        onClick = { NativeController.setMode(choice); mode = choice; NativeController.discovery.stop(this@MainActivity); chosen = null }, label = { Text(choice) })
                } }
                if (mode != "TCP") {
                    OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, SetupActivity::class.java)) }) { Text("Grant Bluetooth permissions") }
                    OutlinedButton(onClick = { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth pairing") }
                    Button(enabled = !busy, onClick = { NativeController.discovery.start(this@MainActivity) { NativeController.failure(it) } }) { Text(if (mode == "BLE") "Scan BLE readers" else "Find bonded devices") }
                    devices.forEach { device -> FilterChip(selected = chosen == device.deviceId, onClick = { chosen = device.deviceId }, label = {Text(device.displayName)}) }
                }
                if (mode == "TCP") OutlinedTextField(host, {host=it}, enabled = state.device == null && !busy, label = {Text("Reader hostname / IP")})
                if (mode == "TCP") OutlinedTextField(port, {port=it}, enabled = state.device == null && !busy, label = {Text("TCP port")})
                Button(enabled = !busy, onClick = { work {
                    if (state.device != null) NativeController.disconnect() else {
                        if (mode == "TCP") NativeController.configure(host, port.toInt())
                        NativeController.connect(if (mode == "TCP") NativeController.devices().first().deviceId else chosen, 5000)
                    }
                } }) { Text(if (state.device == null) "Connect" else "Disconnect") }
                Text("Connected: ${state.device?.detail ?: "No"}\nCard: ${state.status.present} (last observed)\nSelected: ${state.status.selected}")
                Text("HceBox: Settings → Card Transport → Proxy → Scan → Android Native NFC Reader")
                Text(notes, style = MaterialTheme.typography.bodySmall)
            } } }
        }
    }
    override fun onStart() { super.onStart(); bound = bindService(Intent(this, NativeDriverService::class.java), connection, BIND_AUTO_CREATE) }
    override fun onStop() { NativeController.discovery.stop(this); if (bound) unbindService(connection); bound = false; super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
