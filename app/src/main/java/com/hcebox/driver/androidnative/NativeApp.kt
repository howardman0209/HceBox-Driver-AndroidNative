package com.hcebox.driver.androidnative

import android.app.Application
import android.content.Context
import android.util.Log
import com.hcebox.driver.androidnative.connection.NativeController
import com.hcebox.driver.androidnative.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Android owns this process lifetime; no static field retains a Context or controller. */
class NativeApp : Application() {
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val preferences: AppPreferences by lazy {
        AppPreferences.create(this, settingsScope) { Log.d("DriverPreferences", it) }
    }
    val controller: NativeController by lazy { NativeController(this) }
}

/** Returns this app process's settings owner. */
val Context.appPreferences: AppPreferences
    get() = (applicationContext as NativeApp).preferences

/** Returns the process-owned driver instance shared by Activities and the service. */
val Context.nativeController: NativeController
    get() = (applicationContext as NativeApp).controller
