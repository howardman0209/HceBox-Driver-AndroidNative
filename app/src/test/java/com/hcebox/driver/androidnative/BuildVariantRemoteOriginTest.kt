package com.hcebox.driver.androidnative

import com.hcebox.remote.client.RemoteServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class BuildVariantRemoteOriginTest {
    @Test fun remoteOriginMatchesTheVariantAndIsCanonicalHttps() {
        val expected = if (BuildConfig.BUILD_TYPE == "localTest") "https://localhost:18443" else "https://remote.hcebox.com"
        assertEquals(expected, BuildConfig.REMOTE_SERVER_ORIGIN)
        assertEquals(expected, RemoteServerOrigin(BuildConfig.REMOTE_SERVER_ORIGIN).https)
    }
}
