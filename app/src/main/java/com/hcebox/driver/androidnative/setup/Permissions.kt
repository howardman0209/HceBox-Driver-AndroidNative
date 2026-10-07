package com.hcebox.driver.androidnative.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.hcebox.driver.androidnative.connection.ConnectionMode

/**
 * Only the selected transport participates in readiness; TCP on Android 17 requires the LAN runtime
 * grant.
 */
fun missingPermissions(context: Context, mode: ConnectionMode): Array<String> {
    val required =
        when (mode) {
            ConnectionMode.TCP ->
                if (Build.VERSION.SDK_INT >= 37) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK)
                else emptyList()
            ConnectionMode.CLASSIC,
            ConnectionMode.BLE ->
                when {
                    Build.VERSION.SDK_INT >= 31 ->
                        listOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN,
                        )
                    mode == ConnectionMode.BLE -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    else -> emptyList()
                }
        }
    return required
        .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()
}

/** Optional setup grant for visible connection notifications; never affects readiness. */
fun optionalSetupPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    else emptyArray()
