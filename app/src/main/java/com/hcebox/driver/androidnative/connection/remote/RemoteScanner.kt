package com.hcebox.driver.androidnative.connection.remote

import com.hcebox.cardreader.api.DeviceInfo
import com.hcebox.driver.androidnative.connection.ReaderScanner
import com.hcebox.remote.client.RemoteClient
import com.hcebox.remote.client.RemoteHttpFailure
import com.hcebox.remote.client.RemoteServerOrigin
import com.hcebox.reader.protocol.model.ReaderFailure
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds

internal data class RemoteEndpoint(val device: DeviceInfo, val readerId: String, val origin: String, val observedAt: Long)

/** Polls only the selected server; directory snapshots never imply reserved ownership. */
class RemoteScanner(
    private val scope: CoroutineScope,
    private val origin: () -> String,
    private val publish: () -> Unit,
    private val log: (String) -> Unit,
) : ReaderScanner {
    private var job: Job? = null
    private var epoch = 0L
    private var client: RemoteClient? = null
    @Volatile private var records: List<RemoteEndpoint> = emptyList()
    override val isRunning get() = job?.isActive == true

    internal fun endpoints(): List<RemoteEndpoint> = records.filter {
        it.origin == origin() && System.nanoTime() - it.observedAt < 20_000_000_000L
    }

    override fun start(failure: (Throwable) -> Unit) {
        stop()
        val server = try { RemoteServerOrigin(origin()) }
            catch (_: Exception) { throw ReaderFailure(8, "Configure a Remote HTTPS server") }
        val token = epoch
        val http = RemoteClient(server)
        client = http
        records = emptyList()
        publish()
        job = scope.launch {
            try {
                log("Remote discovery started")
                while (isActive && epoch == token) {
                    val directory = withContext(Dispatchers.IO) { http.readers() }
                    if (epoch != token || server.https != origin()) return@launch
                    val now = System.nanoTime()
                    records = directory.readers.map { reader ->
                        RemoteEndpoint(DeviceInfo(remoteDeviceId(server.https, reader.remoteReaderId),
                            reader.displayName, "Remote · ${reader.remoteReaderId.take(8)}"), reader.remoteReaderId, server.https, now)
                    }
                    publish()
                    delay(5.seconds)
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (epoch == token) {
                    records = emptyList()
                    publish()
                    failure(remoteFailure(error))
                }
            } finally { http.close() }
        }
    }

    override fun stop() {
        epoch++
        job?.cancel()
        job = null
        client?.close()
        client = null
    }
}

internal fun remoteDeviceId(origin: String, readerId: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    return "REMOTE:$hash:$readerId"
}

internal fun remoteFailure(error: Exception): ReaderFailure = when (error) {
    is RemoteHttpFailure -> ReaderFailure(if (error.status in listOf(409, 429)) 6 else 5, "Remote server rejected request (${error.status})")
    is TimeoutCancellationException -> ReaderFailure(3, "Remote operation timeout")
    else -> ReaderFailure(5, "Remote connection failed")
}
