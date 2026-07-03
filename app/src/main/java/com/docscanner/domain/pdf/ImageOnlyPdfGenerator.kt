package com.docscanner.domain.pdf

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.docscanner.data.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageOnlyPdfGenerator @Inject constructor() : PdfGenerator {

    override suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
    ): PdfResult = withContext(Dispatchers.IO) {
        try {
            val document = PdfDocument()
            for (page in pages) {
                val bitmap = context.contentResolver.openInputStream(
                    Uri.parse(page.imageUri)
                )?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: continue

                val width = bitmap.width
                val height = bitmap.height

                val pageInfo = PdfDocument.PageInfo.Builder(
                    if (width > height) height else width,
                    if (width > height) width else height,
                    page.pageNumber
                ).create()

                val pdfPage = document.startPage(pageInfo)
                val scaleX = pdfPage.canvas.width.toFloat() / width
                val scaleY = pdfPage.canvas.height.toFloat() / height
                val scale = minOf(scaleX, scaleY)
                val scaledW = (width * scale).toInt()
                val scaledH = (height * scale).toInt()
                val offsetX = (pdfPage.canvas.width - scaledW) / 2f
                val offsetY = (pdfPage.canvas.height - scaledH) / 2f

                pdfPage.canvas.drawBitmap(bitmap, null, android.graphics.RectF(
                    offsetX, offsetY, offsetX + scaledW, offsetY + scaledH
                ), null)
                document.finishPage(pdfPage)
                bitmap.recycle()
            }

            context.contentResolver.openOutputStream(outputUri)?.use { out: OutputStream ->
                document.writeTo(out)
            } ?: return@withContext PdfResult.Error(Exception("Cannot open output stream"))

            document.close()
            PdfResult.Success(outputUri.toString())
        } catch (e: Exception) {
            PdfResult.Error(e)
        }
    }
}
