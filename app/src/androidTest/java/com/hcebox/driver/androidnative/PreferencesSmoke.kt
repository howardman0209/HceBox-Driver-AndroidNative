package com.hcebox.driver.androidnative

import android.content.Context
import com.hcebox.remote.client.createHttpClient
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpStatusCode
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.hcebox.cardreader.api.*
import com.hcebox.driver.androidnative.setup.missingPermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.connection.ReaderDiscovery
import com.hcebox.driver.androidnative.settings.AppPreferences
import java.util.UUID
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

/** Exercises the actual Android migration with isolated legacy/store files, without card I/O. */
internal suspend fun verifyPreferencesMigration(context: Context) {
    val name = "preferences-smoke-${UUID.randomUUID()}"
    val legacy = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    check(legacy.edit().putString("mode", "BLE").putString("host", "fixture.example")
        .putInt("port", 12345).putString("endpoint", "preserved-endpoint").commit())
    val file = context.cacheDir.resolve("$name.preferences_pb")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var original: AppPreferences.Settings
    try {
        val store = PreferenceDataStoreFactory.create(scope = scope,
            migrations = listOf(SharedPreferencesMigration(context, name)), produceFile = { file })
        val preferences = AppPreferences(store, scope)
        val loaded = withTimeout(5.seconds) { preferences.awaitReady() }
        check(loaded.mode == ConnectionMode.BLE && loaded.host == "fixture.example" && loaded.port == 12345)
        check(loaded.endpointId == "preserved-endpoint")
        check(legacy.all.isEmpty()) { "Legacy values were not cleaned up after migration" }
        preferences.configure(" updated.example ", 23456)
        preferences.setMode(ConnectionMode.TCP)
        original = preferences.snapshot()
        check(original.host == "updated.example" && original.port == 23456)
        check(original.endpointId == loaded.endpointId)
    } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
        val store = PreferenceDataStoreFactory.create(scope = restartedScope,
            migrations = listOf(SharedPreferencesMigration(context, name)), produceFile = { file })
        val restarted = AppPreferences(store, restartedScope)
        check(withTimeout(5.seconds) { restarted.awaitReady() } == original)
    } finally { restartedScope.coroutineContext[Job]!!.cancelAndJoin() }
}

/** A stopped discovery must not resume after its settings initialization finishes. */
internal suspend fun verifyDelayedPreferenceDiscovery(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var queried = false
    val owner = Any()
    val discovery = ReaderDiscovery(context, mode = { ConnectionMode.CLASSIC },
        permissions = { emptyArray() }, listDevices = { queried = true; emptyList() },
        log = {}, scope = scope, ready = { entered.complete(Unit); release.await() })
    try {
        discovery.start(owner) { throw it }
        entered.await()
        withContext(Dispatchers.Main) { check(!queried) }
        discovery.stop(owner)
        withContext(Dispatchers.Main) { /* Let the queued stop retire its owner first. */ }
        release.complete(Unit)
        withContext(Dispatchers.Main) { yield(); check(!queried) }
    } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
}

/** Verifies the new mode's public Binder setup behavior without opening a network/card link. */
internal suspend fun verifyRemoteSetupBinder(context: Context) {
    val controller = context.nativeController
    controller.preferences.awaitReady()
    val previous = controller.mode
    val connected = CompletableFuture<ICardReaderDriver>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            connected.complete(ICardReaderDriver.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName?) {}
    }
    var bound = false
    try {
        controller.setMode(ConnectionMode.REMOTE)
        check(missingPermissions(context, ConnectionMode.REMOTE).isEmpty())
        bound = context.bindService(Intent(context, NativeDriverService::class.java), connection, Context.BIND_AUTO_CREATE)
        check(bound)
        val driver = withContext(Dispatchers.IO) { connected.get(5, TimeUnit.SECONDS) }
        check(driver.driverInfo.apiVersion == CardReaderDriverContract.API_VERSION)
        check(driver.driverStatus.readiness == DriverReadiness.READY)
        val result = withContext(Dispatchers.IO) { driver.connectDevice("REMOTE:missing", 500) }
        check(!result.isSuccess && result.error?.code == CardReaderErrorCode.DEVICE_NOT_FOUND)
        check(driver.listConnectedDevices().isEmpty())
    } finally {
        if (bound) context.unbindService(connection)
        controller.setMode(previous)
    }
}

/** Verifies CIO's normal certificate/hostname validation against the owned public website. */
internal suspend fun verifyPublicHttpsRuntime() {
    val http = createHttpClient()
    try {
        withTimeout(10.seconds) {
            http.prepareGet("https://hcebox.com").execute { response ->
                check(response.status == HttpStatusCode.OK) { "Public HTTPS runtime check failed" }
            }
        }
    } finally { http.close() }
}
