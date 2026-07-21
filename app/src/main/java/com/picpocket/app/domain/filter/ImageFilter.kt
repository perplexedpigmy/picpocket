package com.picpocket.app.domain.filter

import android.graphics.Bitmap

interface ImageFilter {
    fun apply(input: Bitmap): Bitmap
}
