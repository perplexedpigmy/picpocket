package com.picpocket.app.domain.scanner

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender

class FakeScannerManager : ScannerManager() {

    var resultToReturn: ScannerResult = ScannerResult.Cancelled
    var startScanError: Throwable? = null
    var lastPageLimit: Int? = null

    override suspend fun getStartScanIntentSender(activity: Activity, pageLimit: Int): IntentSender {
        lastPageLimit = pageLimit
        startScanError?.let { throw it }
        val intent = Intent(activity, activity.javaClass)
        return PendingIntent.getActivity(
            activity,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ).intentSender
    }

    override suspend fun handleResult(data: Intent?): ScannerResult = resultToReturn
}