package com.docscanner.domain.pdf

import android.content.Context
import android.net.Uri
import com.docscanner.data.model.Page

interface PdfGenerator {
    suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
    ): PdfResult
}
