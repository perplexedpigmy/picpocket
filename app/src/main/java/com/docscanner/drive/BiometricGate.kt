package com.docscanner.drive

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BiometricGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val passphraseStore: PassphraseStore,
) {
    private var failureCount = 0
    private val maxFailures = 3

    fun isRequired(): Boolean {
        return encryptionManager.isEncryptionEnabled && passphraseStore.hasPassphrase()
    }

    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFallback: () -> Unit,
    ) {
        if (!isRequired()) {
            onSuccess()
            return
        }

        val biometricManager = BiometricManager.from(context)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val executor = ContextCompat.getMainExecutor(context)
                val prompt = BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            failureCount = 0
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                                onFallback()
                            } else if (errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                                activity.finish()
                            }
                        }

                        override fun onAuthenticationFailed() {
                            failureCount++
                            if (failureCount >= maxFailures) {
                                onFallback()
                            }
                        }
                    },
                )

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock DocScanner")
                    .setSubtitle("Authenticate to access encrypted documents")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                    .build()

                prompt.authenticate(promptInfo)
            }

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                onFallback()
            }
        }
    }
}
