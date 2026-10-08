package com.hcebox.driver.androidnative.settings

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.hcebox.driver.androidnative.connection.ConnectionMode
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPreferencesTest {
    @get:Rule val files = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun stopStore() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }

    private fun create(legacy: Preferences = emptyPreferences()): AppPreferences {
        val migration = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = currentData.asMap().isEmpty()
            override suspend fun migrate(currentData: Preferences) = legacy
            override suspend fun cleanUp() {}
        }
        val store = PreferenceDataStoreFactory.create(
            migrations = listOf(migration), scope = scope,
            produceFile = { files.root.resolve("settings.preferences_pb") },
        )
        return AppPreferences(store, scope)
    }

    @Test fun migrationPreservesIdentityAndValues() = runBlocking {
        val legacy = mutablePreferencesOf(stringPreferencesKey("endpoint") to "existing-id").apply {
            this[stringPreferencesKey("mode")] = "BLE"
            this[stringPreferencesKey("host")] = "saved.example"
            this[intPreferencesKey("port")] = 12345
        }
        val settings = create(legacy).awaitReady()
        assertEquals("existing-id", settings.endpointId)
        assertEquals(ConnectionMode.BLE, settings.mode)
        assertEquals("saved.example", settings.host)
        assertEquals(12345, settings.port)
    }

    @Test fun concurrentInitializationAndAtomicWritesPreserveIdentity() = runBlocking {
        val preferences = create()
        val ids = (1..8).map { async { preferences.awaitReady().endpointId } }.awaitAll()
        assertEquals(1, ids.distinct().size)
        java.util.UUID.fromString(ids.first())
        preferences.configure(" host.example ", 12345)
        assertEquals("host.example", preferences.snapshot().host)
        assertEquals(12345, preferences.snapshot().port)
        preferences.setMode(ConnectionMode.BLE)
        assertEquals(ConnectionMode.BLE, preferences.snapshot().mode)
        assertEquals(ids.first(), preferences.snapshot().endpointId)
    }

    @Test fun processRestartPreservesSavedIdentityAndSettings() = runBlocking {
        val preferences = create()
        val original = preferences.awaitReady()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        val restartedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = restartedScope,
                produceFile = { files.root.resolve("settings.preferences_pb") })
            val restarted = AppPreferences(store, restartedScope)
            assertEquals(original, restarted.awaitReady())
            assertEquals(original.endpointId, restarted.snapshot().endpointId)
        } finally { restartedScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun startupWaitsForMigration() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val migration = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences) = true
            override suspend fun migrate(currentData: Preferences): Preferences {
                entered.complete(Unit)
                release.await()
                return emptyPreferences()
            }
            override suspend fun cleanUp() {}
        }
        val store = PreferenceDataStoreFactory.create(migrations = listOf(migration), scope = scope,
            produceFile = { files.root.resolve("delayed.preferences_pb") })
        val preferences = AppPreferences(store, scope)
        entered.await()
        assertNull(preferences.state.value.settings)
        val waiting = async { preferences.awaitReady() }
        yield()
        assertFalse(waiting.isCompleted)
        release.complete(Unit)
        assertNotNull(waiting.await().endpointId)
    }

    @Test fun storageFailureDoesNotGenerateOrPublishIdentity() = runBlocking {
        var updates = 0
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                updates++
                throw IOException("Fixture read failure")
            }
        }
        val preferences = AppPreferences(store, scope)
        assertTrue(runCatching { preferences.awaitReady() }.exceptionOrNull() is IOException)
        assertNull(preferences.state.value.settings)
        assertEquals(1, updates)
    }

    @Test fun failedWriteKeepsLastDurableSettingsAndCanRetry() = runBlocking {
        var fail = false
        var data = emptyPreferences()
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> get() = flowOf(data)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (fail) throw IOException("Fixture write failure")
                data = transform(data)
                return data
            }
        }
        val preferences = AppPreferences(store, scope)
        val original = preferences.awaitReady()
        fail = true
        val failure = runCatching { preferences.configure("other.example", 12345) }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals(original, preferences.state.value.settings)
        fail = false
        preferences.retry()
        assertEquals(original, preferences.awaitReady())
    }

    @Test fun unknownModeFallsBackToTcp() = runBlocking {
        val preferences = create(mutablePreferencesOf(stringPreferencesKey("mode") to "FUTURE"))
        assertEquals(ConnectionMode.TCP, preferences.awaitReady().mode)
    }

}
