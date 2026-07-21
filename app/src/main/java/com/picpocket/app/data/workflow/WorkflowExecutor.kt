package com.picpocket.app.data.workflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.data.model.AutomationConfig
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.model.TagAutomation
import com.picpocket.app.data.model.WorkflowApp
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.di.SearchablePdf
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.export.PdfGenerator
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowExecutor @Inject constructor(
    private val app: Application,
    private val repository: DocumentRepository,
    @SearchablePdf private val pdfGenerator: PdfGenerator,
) {

    suspend fun execute(document: Document, automations: List<TagAutomation>) {
        for (automation in automations) {
            val pdfUri = generateTempPdf(document) ?: continue
            executeOne(document, pdfUri, automation)
        }
    }

    private suspend fun generateTempPdf(document: Document): String? {
        val pages = repository.getPages(document.id).getOrNull() ?: return null
        val tempDir = File(app.cacheDir, "workflow_exports")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${document.id}.pdf")
        val prefs = app.getSharedPreferences("settings", 0)
        val docPageSize = document.pageSize?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } }
        val pageSize = docPageSize ?: prefs.getString("page_size", PageSize.A4.name)?.let { try { PageSize.valueOf(it) } catch (_: Exception) { null } } ?: PageSize.A4
        val result = pdfGenerator.generate(app, pages, Uri.fromFile(tempFile), pageSize)
        return when (result) {
            is com.picpocket.app.domain.export.PdfResult.Success -> Uri.fromFile(tempFile).toString()
            is com.picpocket.app.domain.export.PdfResult.Error -> null
        }
    }

    private fun executeOne(document: Document, pdfUri: String, automation: TagAutomation) {
        when (automation.app) {
            WorkflowApp.WHATSAPP, WorkflowApp.TELEGRAM, WorkflowApp.VIBER -> shareToChat(document, pdfUri, automation)
            WorkflowApp.GOOGLE_DRIVE -> driveCopy(document, pdfUri, automation)
        }
    }

    private fun shareToChat(@Suppress("UNUSED_PARAMETER") document: Document, pdfUri: String, automation: TagAutomation) {
        try {
            val uri = shareableUri(pdfUri)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(automation.app.packageName)
            }
            if (shareIntent.resolveActivity(app.packageManager) != null) {
                app.startActivity(shareIntent)
            } else {
                app.startActivity(Intent.createChooser(shareIntent, "Share to ${automation.app.displayName}"))
            }
        } catch (e: Exception) {
            showSnackbar("Failed to share to ${automation.app.displayName}: ${e.message}")
        }
    }

    private fun driveCopy(document: Document, pdfUri: String, automation: TagAutomation) {
        try {
            val sourceUri = shareableUri(pdfUri)
            val config = automation.config as AutomationConfig.GoogleDrive
            val folderUri = Uri.parse(config.folderUri)
            val folder = DocumentFile.fromTreeUri(app, folderUri)
            if (folder == null || !folder.exists()) {
                showSnackbar("Target folder not found")
                return
            }
            val safeName = document.name.replace(" ", "_").replace("/", "_") + ".pdf"
            val existing = folder.findFile(safeName)
            if (existing != null) existing.delete()
            val newFile = folder.createFile("application/pdf", safeName) ?: run {
                showSnackbar("Failed to create file in target folder")
                return
            }
            app.contentResolver.openInputStream(sourceUri)?.use { input ->
                app.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            showSnackbar("PDF saved to ${config.displayName}")
        } catch (e: Exception) {
            showSnackbar("Copy failed: ${e.message}")
        }
    }

    private fun shareableUri(pdfUri: String): Uri {
        val uri = Uri.parse(pdfUri)
        if (uri.scheme == "file") {
            val file = File(uri.path!!)
            return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        }
        return uri
    }

    private fun showSnackbar(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
