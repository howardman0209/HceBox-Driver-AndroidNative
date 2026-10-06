package com.hcebox.driver.androidnative

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Only the selected transport participates in readiness; TCP needs no runtime grant. */
fun missingPermissions(context: Context): Array<String> {
    val required = when {
        NativeController.mode == "TCP" -> emptyList()
        Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        NativeController.mode == "BLE" -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }
    return required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
}
