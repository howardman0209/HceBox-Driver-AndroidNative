package com.hcebox.driver.androidnative

import android.content.*
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hcebox.driver.androidnative.ui.DriverApp
import com.hcebox.driver.androidnative.ui.theme.NativeDriverTheme

/** UI navigation does not own the service's reader connection. */
class MainActivity : ComponentActivity() {
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NativeDriverTheme { DriverApp(nativeController) } }
    }
    override fun onStart() { super.onStart(); bound = bindService(Intent(this, NativeDriverService::class.java), connection, BIND_AUTO_CREATE) }
    override fun onStop() { if (bound) unbindService(connection); bound = false; super.onStop() }
}
