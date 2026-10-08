package com.hcebox.driver.androidnative

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.hcebox.remote.client.RemoteClient
import com.hcebox.remote.client.RemoteServerOrigin
import com.hcebox.reader.protocol.codec.Codec
import com.hcebox.reader.protocol.model.Frame
import com.hcebox.reader.protocol.model.Type
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/** Real Android HTTPS/WSS smoke; no NFC, Binder transaction or APDU is exercised. */
class LocalRemoteInstrumentation : Instrumentation() {
    private var origin = ""

    override fun onCreate(arguments: Bundle?) {
        origin = BuildConfig.REMOTE_SERVER_ORIGIN
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            check(BuildConfig.BUILD_TYPE == "localTest") { "Local test variant required" }
            runBlocking { withTimeout(15.seconds) { verifyRemote() } }
            results.putString("stream", "Local Android HTTPS/WSS discovery, match and binary forwarding passed; no APDU sent.\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Exception) {
            results.putString("stream", "Local Remote smoke failed: ${error.javaClass.simpleName}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private suspend fun verifyRemote() {
        val client = RemoteClient(RemoteServerOrigin(origin))
        try {
            val reader = client.register("Local TLS smoke", true)
            try {
                val id = checkNotNull(reader.remoteReaderId)
                // REGISTERED can arrive just before the server publishes directory availability.
                while (client.readers().readers.none { it.remoteReaderId == id }) delay(50.milliseconds)
                val driver = client.connect(id)
                try {
                    reader.awaitMatch()
                    reader.sessionReady()
                    val ping = Codec.encode(Frame(Type.PING, 1))
                    driver.sendBinary(ping)
                    check(reader.receiveBinary().contentEquals(ping))
                    val pong = Codec.encode(Frame(Type.PONG, 1))
                    reader.sendBinary(pong)
                    check(driver.receiveBinary().contentEquals(pong))
                } finally { driver.close() }
            } finally { reader.close() }
        } finally { client.close() }
    }
}
