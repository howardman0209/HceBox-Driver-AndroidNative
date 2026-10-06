package com.hcebox.driver.androidnative

import android.os.Bundle
import androidx.activity.ComponentActivity

/** TCP needs no runtime permission; Bluetooth setup is added with its transports. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setResult(RESULT_OK); finish()
    }
}
