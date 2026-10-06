package com.hcebox.driver.androidnative

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Transparent permission-only entry point shared by HceBox and the driver's UI. */
class SetupActivity : ComponentActivity() {
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (optionalSetupPermissions().any { results[it] == false }) {
                Log.d("NativeSetup", "Optional notification permission was not granted")
            }
            finishWithResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val required =
            missingPermissions(this) +
                optionalSetupPermissions().filter {
                    checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
        if (required.isEmpty()) finishWithResult()
        else if (savedInstanceState == null) {
            Log.d("NativeSetup", "Requesting setup permissions for selected transport")
            permissionRequest.launch(required)
        }
    }

    private fun finishWithResult() {
        // Notification denial must not block the selected transport.
        setResult(if (missingPermissions(this).isEmpty()) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
