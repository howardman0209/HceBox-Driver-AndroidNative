package com.hcebox.driver.androidnative

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.hcebox.driver.androidnative.connection.ConnectionMode
import com.hcebox.driver.androidnative.connection.ReaderDiscovery
import com.hcebox.driver.androidnative.settings.AppPreferences
import java.util.UUID
import kotlinx.coroutines.*

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
        val loaded = withTimeout(5000) { preferences.awaitReady() }
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
        check(withTimeout(5000) { restarted.awaitReady() } == original)
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
