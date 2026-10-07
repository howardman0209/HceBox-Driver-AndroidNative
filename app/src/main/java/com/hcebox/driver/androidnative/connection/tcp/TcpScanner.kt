package com.hcebox.driver.androidnative.connection.tcp

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ReaderScanner
import com.hcebox.reader.protocol.contract.LanContract
import com.hcebox.reader.protocol.core.Deadline
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Resolved endpoint data belongs to the selected device, never the manual global settings. */
data class TcpEndpoint(
    val device: DeviceInfo,
    val hosts: List<String>,
    val port: Int,
    val network: Network? = null,
    val installationId: String? = null,
)

/**
 * Owns one NSD scan on the supplied queued Main scope; legacy resolutions are serialized and late
 * callbacks are fenced. Stop discovery before cancelling the owner scope.
 */
@Suppress("DEPRECATION")
class TcpScanner(
    context: Context,
    private val changed: () -> Unit,
    private val log: (String) -> Unit,
    private val scope: CoroutineScope,
) : ReaderScanner {
    private val manager = context.getSystemService(NsdManager::class.java)
    // NSD requires an Executor; keep its callbacks queued on the owner's Main scope.
    private val executor =
        java.util.concurrent.Executor { command ->
            scope.launch { command.run() }
        }
    private val multicast =
        context
            .getSystemService(WifiManager::class.java)
            ?.createMulticastLock("NativeDriverNsd")
            ?.apply { setReferenceCounted(false) }

    // Resolve results can rewrite the service type; retain discovery input for every refresh.
    private data class Record(val source: NsdServiceInfo, val endpoint: TcpEndpoint)

    private val records = ConcurrentHashMap<String, Record>()
    private val liveServices = mutableSetOf<String>()
    private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0L

    private data class Resolve(
        val info: NsdServiceInfo,
        val token: Long?,
        val expectedId: String?,
        val result: CompletableFuture<TcpEndpoint>,
        val sourceKey: String,
    )

    private val queue = java.util.ArrayDeque<Resolve>()
    private var resolving = false
    override val isRunning
        get() = listener != null

    fun endpoints(): List<TcpEndpoint> =
        records.values.map { it.endpoint }.distinctBy { it.device.deviceId }

    fun endpoint(id: String): TcpEndpoint? = endpoints().firstOrNull { it.device.deviceId == id }

    override fun start(failure: (Throwable) -> Unit) {
        if (listener != null) return
        records.clear()
        changed()
        val token = ++generation
        val callback =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    log("NSD discovery started")
                }

                override fun onDiscoveryStopped(type: String) {
                    log("NSD discovery stopped")
                }

                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    scope.launch {
                        if (token == generation) {
                            stop()
                            failure(IllegalStateException("NSD start failed code=$errorCode"))
                        }
                    }
                }

                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                    log("NSD stop failed code=$errorCode")
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    scope.launch {
                        if (token != generation || listener == null) return@launch
                        if (info.serviceType.trimEnd('.') != LanContract.SERVICE_TYPE.trimEnd('.'))
                            return@launch
                        if (liveServices.size >= 32 && !liveServices.contains(key(info))) {
                            log("NSD service limit reached")
                            return@launch
                        }
                        liveServices.add(key(info))
                        if (Build.VERSION.SDK_INT >= 34) watch(info, token)
                        else enqueue(Resolve(info, token, null, CompletableFuture(), key(info)))
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    scope.launch {
                        if (token != generation) return@launch
                        val key = key(info)
                        liveServices.remove(key)
                        if (Build.VERSION.SDK_INT >= 34)
                            callbacks.remove(key)?.let {
                                runCatching { manager.unregisterServiceInfoCallback(it) }
                            }
                        records.remove(key)
                        changed()
                        log("NSD service lost name=${info.serviceName}")
                    }
                }
            }
        listener = callback
        try {
            multicast?.acquire()
            manager.discoverServices(LanContract.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (error: Exception) {
            stop()
            failure(error)
        }
    }

    /** Re-resolves under the original connect budget, including after discovery has stopped. */
    fun refresh(id: String, deadline: Deadline): TcpEndpoint {
        val entry =
            records.entries.firstOrNull { it.value.endpoint.device.deviceId == id }
                ?: throw IllegalArgumentException("NSD endpoint no longer available")
        val record = entry.value
        val result = CompletableFuture<TcpEndpoint>()
        scope.launch {
            enqueue(Resolve(record.source, null, record.endpoint.installationId, result, entry.key))
        }
        return try {
            result.get(deadline.remaining().toLong(), TimeUnit.MILLISECONDS)
        } finally {
            if (!result.isDone) result.cancel(false)
        }
    }

    private fun enqueue(task: Resolve) {
        if (queue.size >= 32) {
            task.result.completeExceptionally(IllegalStateException("NSD resolve queue full"))
            return
        }
        if (task.token == null) queue.addFirst(task) else queue.addLast(task)
        pump()
    }

    private fun pump() {
        if (resolving) return
        val task = queue.pollFirst() ?: return
        if (task.result.isCancelled || (task.token != null && task.token != generation)) {
            task.result.cancel(false)
            pump()
            return
        }
        resolving = true
        log("NSD resolve name=${task.info.serviceName} type=${task.info.serviceType}")
        val callback =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    scope.launch {
                        log(
                            "NSD resolve failed name=${task.info.serviceName} type=${task.info.serviceType} code=$errorCode"
                        )
                        resolving = false
                        task.result.completeExceptionally(
                            IllegalStateException("NSD resolve failed code=$errorCode")
                        )
                        pump()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    scope.launch {
                        resolving = false
                        if (
                            !task.result.isCancelled &&
                                (task.token == null ||
                                    (task.token == generation &&
                                        liveServices.contains(task.sourceKey)))
                        ) {
                            try {
                                val endpoint =
                                    parse(info)
                                        ?: throw IllegalArgumentException(
                                            "Incompatible NSD metadata"
                                        )
                                check(
                                    task.expectedId == null ||
                                        endpoint.installationId == task.expectedId
                                ) {
                                    "NSD identity changed"
                                }
                                if (
                                    (task.token == null && records.containsKey(task.sourceKey)) ||
                                        (task.token != null && listener != null)
                                ) {
                                    records[task.sourceKey] = Record(task.info, endpoint)
                                    changed()
                                }
                                task.result.complete(endpoint)
                            } catch (error: Exception) {
                                task.result.completeExceptionally(error)
                                log("NSD resolve ignored: ${error.message}")
                            }
                        } else task.result.cancel(false)
                        pump()
                    }
                }
            }
        try {
            manager.resolveService(task.info, callback)
        } catch (error: Exception) {
            resolving = false
            task.result.completeExceptionally(error)
            pump()
        }
    }

    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo, token: Long) {
        val key = key(info)
        if (callbacks.containsKey(key)) return
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    scope.launch {
                        if (token == generation) {
                            callbacks.remove(key)
                            log("NSD updates unavailable code=$errorCode")
                            enqueue(Resolve(info, token, null, CompletableFuture(), key))
                        }
                    }
                }

                override fun onServiceInfoCallbackUnregistered() {}

                override fun onServiceLost() {
                    scope.launch {
                        if (token == generation) {
                            records.remove(key)
                            changed()
                        }
                    }
                }

                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    scope.launch {
                        if (token != generation || listener == null) return@launch
                        val endpoint = parse(updated)
                        if (endpoint != null) {
                            records[key] = Record(info, endpoint)
                            changed()
                            log("NSD resolved ${endpoint.device.detail}")
                        } else {
                            records.remove(key)
                            changed()
                        }
                    }
                }
            }
        callbacks[key] = callback
        try {
            manager.registerServiceInfoCallback(info, executor, callback)
        } catch (error: Exception) {
            callbacks.remove(key)
            enqueue(Resolve(info, token, null, CompletableFuture(), key))
        }
    }

    private fun parse(info: NsdServiceInfo): TcpEndpoint? {
        val id = LanContract.installationId(info.attributes) ?: return null
        if (info.port !in 1..65535) return null
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        if (addresses.isEmpty()) return null
        val network = if (Build.VERSION.SDK_INT >= 33) info.network else null
        return TcpEndpoint(
            DeviceInfo(
                "TCP:NSD:$id",
                info.serviceName,
                "TCP NSD ${addresses.joinToString { it.hostAddress ?: "" }}:${info.port}",
            ),
            addresses.mapNotNull { it.hostAddress },
            info.port,
            network,
            id,
        )
    }

    private fun key(info: NsdServiceInfo) =
        "${info.serviceName}|${info.serviceType}|${if (Build.VERSION.SDK_INT >= 33) info.network?.networkHandle else null}"

    override fun stop() {
        generation++
        val old = listener
        listener = null
        if (old != null) runCatching { manager.stopServiceDiscovery(old) }
        if (Build.VERSION.SDK_INT >= 34)
            callbacks.values.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
        callbacks.clear()
        liveServices.clear()
        queue.removeIf { it.token != null }
        if (multicast?.isHeld == true) multicast.release()
        // Keep last resolved endpoints for a selected device; connect refreshes them before use.
    }
}
