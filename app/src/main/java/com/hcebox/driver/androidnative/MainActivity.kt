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
    private val controller get() = nativeController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by controller.view.collectAsStateWithLifecycle()
            val notes by controller.notes.collectAsStateWithLifecycle()
            var mode by remember { mutableStateOf(controller.mode) }
            val devices by controller.discovery.devices.collectAsStateWithLifecycle()
            var chosen by remember { mutableStateOf<String?>(null) }
            var host by remember { mutableStateOf(controller.host) }
            var port by remember { mutableStateOf(controller.port.toString()) }
            var busy by remember { mutableStateOf(false) }
            fun work(action: () -> Unit) { busy = true; scope.launch {
                withContext(Dispatchers.IO) { runCatching(action).onFailure { controller.failure(it) } }; busy = false
            } }
            MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Android Native Driver", style = MaterialTheme.typography.headlineSmall)
                Row { listOf("TCP", "CLASSIC", "BLE").forEach { choice ->
                    FilterChip(selected = mode == choice, enabled = state.device == null && !busy,
                        onClick = { controller.setMode(choice); mode = choice; controller.discovery.stop(this@MainActivity); chosen = null }, label = { Text(choice) })
                } }
                OutlinedButton(onClick = { startActivity(Intent(this@MainActivity, SetupActivity::class.java)) }) { Text("Grant transport permissions") }
                if (mode != "TCP") OutlinedButton(onClick = { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth pairing") }
                Button(enabled = !busy, onClick = { controller.discovery.start(this@MainActivity) { controller.failure(it) } }) {
                    Text(when(mode) { "TCP" -> "Find readers on network"; "BLE" -> "Scan BLE readers"; else -> "Find bonded devices" })
                }
                devices.forEach { device -> FilterChip(selected = chosen == device.deviceId, enabled = state.device == null && !busy,
                    onClick = { chosen = device.deviceId }, label = {Text("${device.displayName} · ${device.detail ?: ""}")}) }
                if (mode == "TCP") Text("Manual IP / port fallback (optional)")
                if (mode == "TCP") OutlinedTextField(host, {host=it; chosen=null}, enabled = state.device == null && !busy, label = {Text("Reader hostname / IP")})
                if (mode == "TCP") OutlinedTextField(port, {port=it; chosen=null}, enabled = state.device == null && !busy, label = {Text("TCP port")})
                Button(enabled = !busy, onClick = { work {
                    if (state.device != null) controller.disconnect() else {
                        val id = chosen ?: if (mode == "TCP") {
                            controller.configure(host, port.toInt())
                            controller.devices().first { !it.deviceId.startsWith("TCP:NSD:") }.deviceId
                        } else null
                        controller.connect(id, 5000)
                    }
                } }) { Text(if (state.device == null) "Connect" else "Disconnect") }
                Text("Connected: ${state.device?.detail ?: "No"}\nCard: ${state.status.present} (last observed)\nSelected: ${state.status.selected}\nReader error: ${controller.readerStatus()?.lastError?.code ?: "None"}")
                Text("HceBox: Settings → Card Transport → Proxy → Scan → Android Native NFC Reader")
                Text(notes, style = MaterialTheme.typography.bodySmall)
            } } }
        }
    }
    override fun onStart() { super.onStart(); bound = bindService(Intent(this, NativeDriverService::class.java), connection, BIND_AUTO_CREATE) }
    override fun onStop() { controller.discovery.stop(this); if (bound) unbindService(connection); bound = false; super.onStop() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
