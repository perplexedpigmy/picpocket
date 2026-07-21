package com.picpocket.app.drive

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PassphraseStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "drive_passphrase_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun savePassphrase(passphrase: String) {
        prefs.edit().putString(KEY_PASSPHRASE, passphrase).apply()
    }

    fun getPassphrase(): String? {
        return prefs.getString(KEY_PASSPHRASE, null)
    }

    fun hasPassphrase(): Boolean = prefs.contains(KEY_PASSPHRASE)

    fun clearPassphrase() {
        prefs.edit().remove(KEY_PASSPHRASE).apply()
    }

    companion object {
        private const val KEY_PASSPHRASE = "drive_encryption_passphrase"
    }
}
