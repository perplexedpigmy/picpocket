package com.picpocket.app.domain.scan

import android.graphics.BitmapFactory
import java.io.File

object PageEncoder {

    fun encodePage(source: File, destination: File, tier: QualityTier) {
        if (tier == QualityTier.BEST) {
            source.copyTo(destination, overwrite = true)
            return
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
        if (bitmap != null) {
            destination.outputStream().use { out ->
                bitmap.compress(tier.format, tier.quality, out)
            }
        } else {
            source.copyTo(destination, overwrite = true)
        }
    }
}
