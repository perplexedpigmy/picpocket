@file:Suppress("DEPRECATION")

package com.picpocket.app.domain.scan

import android.graphics.Bitmap

enum class QualityTier(
    val label: String,
    val description: String,
    val estimatedReduction: String,
    internal val quality: Int,
    internal val format: Bitmap.CompressFormat,
) {
    BEST("Best", "No re-compression", "0%", 100, Bitmap.CompressFormat.JPEG),
    HIGH("High", "WebP 90% quality", "~50%", 90, Bitmap.CompressFormat.WEBP),
    MEDIUM("Medium", "JPEG 75% quality", "~70%", 75, Bitmap.CompressFormat.JPEG),
    COMPACT("Compact", "JPEG 50% quality", "~85%", 50, Bitmap.CompressFormat.JPEG),
}
