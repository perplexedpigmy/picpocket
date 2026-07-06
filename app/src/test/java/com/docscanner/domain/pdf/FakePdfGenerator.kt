package com.docscanner.domain.pdf

import android.content.Context
import android.net.Uri
import com.docscanner.data.model.Page

class FakePdfGenerator : PdfGenerator {

    var shouldFail = false
    val generatedPages = mutableListOf<List<Page>>()

    override suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize,
    ): PdfResult {
        generatedPages.add(pages)
        return if (shouldFail) {
            PdfResult.Error(Exception("Fake PDF generation failed"))
        } else {
            PdfResult.Success(outputUri.toString())
        }
    }
}
