package com.channingchen.keepasssd

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Transparent proxy Activity that isolates the Key Driver (YubiKey) intent call
 * from MainActivity. This is the KeePassDX pattern:
 *
 *   MainActivity (stays in foreground) → KeyDriverProxyActivity (transparent, invisible)
 *                                           ↓
 *                                    Key Driver App (NFC/USB)
 *                                           ↓
 *                              Result relayed via MainViewModel SharedFlow
 *                                           ↓
 *                                   ProxyActivity finishes()
 *
 * Because MainActivity never leaves the foreground, the "UI disappears on fast return" bug
 * is eliminated.
 */
class KeyDriverProxyActivity : ComponentActivity() {

    private val driverLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Relay result to the ViewModel, then vanish
        val viewModel = MainViewModel.instance
        val response = if (result.resultCode == RESULT_OK) {
            result.data?.getByteArrayExtra("response")
        } else {
            null
        }
        viewModel?.onHardwareKeyResponse(response)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driverIntent = intent.getParcelableExtra<Intent>(EXTRA_DRIVER_INTENT)
        if (driverIntent == null) {
            MainViewModel.instance?.onHardwareKeyResponse(null)
            finish()
            return
        }
        tryLaunchDriver(driverIntent)
    }

    private fun tryLaunchDriver(intent: Intent) {
        try {
            driverLauncher.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // Fallback to ykDroid
            if (intent.action != "net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE") {
                val fallback = Intent("net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE").apply {
                    putExtras(intent)
                }
                try {
                    driverLauncher.launch(fallback)
                } catch (e2: android.content.ActivityNotFoundException) {
                    MainViewModel.instance?.onHardwareKeyError(
                        "No Hardware Key Driver installed. Please install KeePassDX Key Driver or ykDroid."
                    )
                    finish()
                }
            } else {
                MainViewModel.instance?.onHardwareKeyError(
                    "No Hardware Key Driver installed. Please install KeePassDX Key Driver or ykDroid."
                )
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_DRIVER_INTENT = "driver_intent"
    }
}
