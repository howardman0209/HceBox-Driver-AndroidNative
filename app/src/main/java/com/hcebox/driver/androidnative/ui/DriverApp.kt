package com.hcebox.driver.androidnative.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcebox.cardreader.api.DriverError
import com.hcebox.driver.androidnative.*
import com.hcebox.driver.androidnative.R
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.connection.NativeController
import com.hcebox.driver.androidnative.setup.missingPermissions
import com.hcebox.driver.androidnative.ui.screens.*
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
    var permitted by remember {
        mutableStateOf(missingPermissions(context, controller.mode).isEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<DriverError?>(null) }
    var lastId by rememberSaveable { mutableStateOf<String?>(null) }
    var host by rememberSaveable { mutableStateOf(controller.host) }
    var port by rememberSaveable { mutableStateOf(controller.port.toString()) }
    val owner = remember { Any() }
    val setup =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permitted = missingPermissions(context, controller.mode).isEmpty()
            refresh++
        }
    DisposableEffect(lifecycle, mode) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mode = controller.mode
                permitted = missingPermissions(context, controller.mode).isEmpty()
                refresh++
            }
            if (event == Lifecycle.Event.ON_STOP) controller.discovery.stop(owner)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            controller.discovery.stop(owner)
        }
    }
    DisposableEffect(page, mode, permitted, refresh) {
        if (page == "Readers" && permitted) {
            scanning = mode != ConnectionMode.CLASSIC
            val scanMode = mode
            controller.discovery.start(owner) {
                // A transport switch retires the previous scan; it is not a connection failure.
                if (controller.mode == scanMode) {
                    error = controller.failure(it)
                    scanning = false
                }
            }
        }
        onDispose {
            controller.discovery.stop(owner)
            scanning = false
        }
    }
    LaunchedEffect(state.device) {
        state.device?.let { lastId = it.deviceId }
    }
    LaunchedEffect(page) { controller.log("UI page opened: $page") }
    fun connect(id: String?, manual: Boolean = false) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target =
                        if (manual) {
                            controller.configure(
                                host,
                                port.toIntOrNull()
                                    ?: throw IllegalArgumentException("Invalid port"),
                            )
                            controller
                                .devices()
                                .first { !it.deviceId.startsWith("TCP:NSD:") }
                                .deviceId
                        } else id
                    controller.connect(target, 5000)
                }
                page = "Reader details"
            } catch (failure: Exception) {
                error = controller.failure(failure)
            } finally {
                busy = false
            }
        }
    }
    fun back() {
        page = "Readers"
    }
    BackHandler(enabled = page != "Readers") { back() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (page == "Readers") "Native Driver" else page) },
                navigationIcon = {
                    if (page in listOf("Reader details", "Manual connection"))
                        IconButton(onClick = { back() }) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
                        }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("Readers", "Settings", "Diagnostics").forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected =
                            if (index == 0)
                                page in listOf("Readers", "Reader details", "Manual connection")
                            else page == destination,
                        enabled = !busy,
                        onClick = { page = destination },
                        label = { Text(destination) },
                        icon = {
                            Icon(
                                painterResource(
                                    listOf(
                                        R.drawable.ic_readers,
                                        R.drawable.ic_settings,
                                        R.drawable.ic_diagnostics,
                                    )[index]
                                ),
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val visibleError = error ?: state.error
            if (visibleError != null && page != "Diagnostics") {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                ) {
                    Text(friendlyError(visibleError), Modifier.padding(16.dp))
                }
            }
            when (page) {
                "Readers" ->
                    ReadersScreen(
                        mode,
                        permitted,
                        state,
                        devices,
                        scanning,
                        busy,
                        onMode = {
                            controller.setMode(it)
                            mode = it
                            error = null
                            permitted = missingPermissions(context, controller.mode).isEmpty()
                        },
                        onSetup = { setup.launch(Intent(context, SetupActivity::class.java)) },
                        onDetails = { page = "Reader details" },
                        onConnect = {
                            lastId = it
                            connect(it)
                        },
                        onRescan = {
                            error = null
                            refresh++
                        },
                        onManual = { page = "Manual connection" },
                    )
                "Manual connection" ->
                    ManualConnectionScreen(
                        host,
                        port,
                        busy,
                        onHost = { host = it },
                        onPort = { port = it },
                        onConnect = { connect(null, true) },
                    )
                "Reader details" ->
                    ReaderDetailsScreen(
                        state,
                        mode,
                        busy,
                        lastId != null,
                        onDisconnect = {
                            controller.disconnect()
                            error = null
                            page = "Readers"
                        },
                        onReconnect = { connect(lastId) },
                    )
                "Settings" ->
                    DriverSettingsScreen(
                        mode,
                        onSetup = { setup.launch(Intent(context, SetupActivity::class.java)) },
                    )
                "Diagnostics" ->
                    DriverDiagnosticsScreen(
                        state,
                        visibleError,
                        notes,
                        onClear = { controller.notes.value = "" },
                    )
            }
        }
    }
}
