package com.sdm.downloadmanager.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Asks for the fingerprint/face or the screen lock (PIN, pattern, password). Uses the platform
 * prompt, so no extra library is needed. Calls [onResult] with true only after a successful unlock.
 */
object DeviceLock {
    fun available(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun authenticate(activity: Activity, title: String, onResult: (Boolean) -> Unit) {
        if (!available(activity)) { onResult(false); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = BiometricPrompt.Builder(activity).setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
            prompt.authenticate(CancellationSignal(), activity.mainExecutor, callback(onResult))
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            val prompt = BiometricPrompt.Builder(activity).setTitle(title).setDeviceCredentialAllowed(true).build()
            prompt.authenticate(CancellationSignal(), activity.mainExecutor, callback(onResult))
        } else {
            // Android 8–9: the screen-lock confirmation screen.
            @Suppress("DEPRECATION")
            val intent = activity.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null)
            if (intent == null) { onResult(true); return }
            pendingLegacy = onResult
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_LEGACY)
        }
    }

    private fun callback(onResult: (Boolean) -> Unit) = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onResult(true)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
    }

    const val REQUEST_LEGACY = 4711
    private var pendingLegacy: ((Boolean) -> Unit)? = null

    /** Forwarded from the activity on Android 8–9. */
    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_LEGACY) return
        pendingLegacy?.invoke(resultCode == Activity.RESULT_OK)
        pendingLegacy = null
    }
}
