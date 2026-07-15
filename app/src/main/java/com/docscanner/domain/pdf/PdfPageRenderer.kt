package com.docscanner.domain.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.docscanner.data.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class PdfPageRenderer @Inject constructor() {

    open suspend fun renderAllPages(
        context: Context,
        pdfUri: Uri,
        pages: List<Page>,
        destDir: File,
        onPageRendered: suspend (pageId: Long, newUri: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val pfd = try {
            context.contentResolver.openFileDescriptor(pdfUri, "r")
        } catch (_: Exception) { null } ?: return@withContext

        val renderer = try {
            PdfRenderer(pfd)
        } catch (_: Exception) {
            pfd.close()
            return@withContext
        }

        try {
            destDir.mkdirs()
            for (page in pages) {
                val pageFile = File(destDir, "${page.id}.jpg")
                if (pageFile.exists()) continue

                try {
                    val pdfPage = renderer.openPage(page.pageNumber - 1)
                    val maxDimension = 4096
                    val scale = minOf(
                        300f / 72f,
                        maxDimension.toFloat() / maxOf(pdfPage.width, pdfPage.height),
                    )
                    val bitmap = Bitmap.createBitmap(
                        (pdfPage.width * scale).toInt(),
                        (pdfPage.height * scale).toInt(),
                        Bitmap.Config.ARGB_8888,
                    )
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    FileOutputStream(pageFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    bitmap.recycle()
                    pdfPage.close()

                    val newUri = Uri.fromFile(pageFile).toString()
                    onPageRendered(page.id, newUri)
                } catch (_: Exception) { }
            }
        } finally {
            renderer.close()
        }
    }
}
