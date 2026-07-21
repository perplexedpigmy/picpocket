package com.picpocket.app.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveConnectivityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isPlayServicesAvailable(): Boolean {
        val result = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        return result == ConnectionResult.SUCCESS
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
