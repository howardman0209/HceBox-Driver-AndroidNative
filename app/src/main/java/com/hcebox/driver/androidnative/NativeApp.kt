package com.hcebox.driver.androidnative

import android.app.Application
import android.content.Context

/** Android owns this process lifetime; no static field retains a Context or controller. */
class NativeApp : Application() {
    val controller: NativeController by lazy { NativeController(this) }
}

/** Returns the process-owned driver instance shared by Activities and the service. */
val Context.nativeController: NativeController
    get() = (applicationContext as NativeApp).controller
