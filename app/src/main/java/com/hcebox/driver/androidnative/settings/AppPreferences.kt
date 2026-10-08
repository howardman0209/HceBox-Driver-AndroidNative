package com.hcebox.driver.androidnative.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import com.hcebox.driver.androidnative.connection.ConnectionMode
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns persisted app settings and legacy migration; callers never access storage keys. */
class AppPreferences internal constructor(
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    data class Settings(
        val mode: ConnectionMode = ConnectionMode.TCP,
        val host: String = "",
        val port: Int = 35965,
        val endpointId: String,
    )

    data class State(val settings: Settings? = null, val error: Throwable? = null)

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var loading: Job? = null

    init {
        retry()
    }

    /** Retries failed initialization without replacing any previously stored identity. */
    @Synchronized
    fun retry() {
        if (loading?.isActive == true && mutableState.value.error == null) return
        loading?.cancel()
        mutableState.value = State()
        loading = scope.launch {
            try {
                writes.withLock {
                    // edit waits for migration and creates a missing ID in the same transaction.
                    val data = store.edit { if (it[ID] == null) it[ID] = UUID.randomUUID().toString() }
                    mutableState.value = State(decode(data))
                    log("App preferences initialized")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = State(error = error)
                log("App preferences initialization failed: ${error.javaClass.simpleName}")
            }
        }
    }

    /** Awaits persisted settings or throws the initialization error; cancellation remains effective. */
    suspend fun awaitReady(): Settings {
        val loaded = state.first { it.settings != null || it.error != null }
        loaded.error?.let { throw it }
        return checkNotNull(loaded.settings)
    }

    /** Returns only initialized settings, without performing disk I/O. */
    fun snapshot(): Settings {
        val loaded = state.value
        loaded.error?.let { throw IllegalStateException("App preferences unavailable", it) }
        return checkNotNull(loaded.settings) { "App preferences are loading" }
    }

    /** Saves the selected transport; returns only after the setting is durable. */
    suspend fun setMode(mode: ConnectionMode) = update { it[MODE] = mode.name }

    /** Saves a validated manual TCP endpoint atomically without rotating its identity. */
    suspend fun configure(host: String, port: Int) {
        require(host.isNotBlank() && port in 1..65535) { "Valid host and port required" }
        update {
            it[HOST] = host.trim()
            it[PORT] = port
        }
    }

    private suspend fun update(change: (MutablePreferences) -> Unit) {
        awaitReady()
        writes.withLock {
            try {
                val data = store.edit { change(it) }
                mutableState.value = State(decode(data))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = State(mutableState.value.settings, error)
                log("App preferences save failed: ${error.javaClass.simpleName}")
                throw error
            }
        }
    }

    private fun decode(data: Preferences) =
        Settings(
            ConnectionMode.entries.firstOrNull { it.name == data[MODE] } ?: ConnectionMode.TCP,
            data[HOST] ?: "",
            data[PORT] ?: 35965,
            checkNotNull(data[ID]),
        )

    companion object {
        private val MODE = stringPreferencesKey("mode")
        private val HOST = stringPreferencesKey("host")
        private val PORT = intPreferencesKey("port")
        private val ID = stringPreferencesKey("endpoint")

        /** Creates the app's single store, migrating legacy settings before the first read/write. */
        fun create(context: Context, scope: CoroutineScope, log: (String) -> Unit): AppPreferences {
            val app = context.applicationContext
            val store = PreferenceDataStoreFactory.create(
                migrations = listOf(SharedPreferencesMigration(app, "driver")),
                scope = scope,
                produceFile = { app.preferencesDataStoreFile("app_settings") },
            )
            return AppPreferences(store, scope, log)
        }
    }
}
