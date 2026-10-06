package com.hcebox.driver.androidnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Central permission-only entry point for both HceBox and the driver's UI. */
class SetupActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        setResult(if (missingPermissions(this).isEmpty()) RESULT_OK else RESULT_CANCELED); finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); NativeController.init(this)
        val required = missingPermissions(this)
        if (required.isEmpty()) { setResult(RESULT_OK); finish() } else permissionRequest.launch(required)
    }
}
