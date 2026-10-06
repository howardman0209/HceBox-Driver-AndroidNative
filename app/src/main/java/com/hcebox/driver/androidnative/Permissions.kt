package com.hcebox.driver.androidnative

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Only the selected transport participates in readiness; TCP on Android 17 requires the LAN runtime grant. */
fun missingPermissions(context: Context): Array<String> {
    val required = when {
        context.nativeController.mode == "TCP" -> if (Build.VERSION.SDK_INT >= 37) listOf(Manifest.permission.ACCESS_LOCAL_NETWORK) else emptyList()
        Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        context.nativeController.mode == "BLE" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }
    return required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
}

/** Optional setup grant for visible connection notifications; never affects readiness. */
fun optionalSetupPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
