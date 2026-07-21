package com.picpocket.app.domain.export

import android.content.Context
import android.net.Uri
import com.picpocket.app.data.model.Page

interface PdfGenerator {
    suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize = PageSize.A4,
    ): PdfResult
}
