package com.docscanner

import android.app.Application
import com.docscanner.data.repository.DocumentRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class DocScannerApp : Application() {

    @Inject lateinit var repository: DocumentRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val trackedIds = repository.getAllDocuments().map { it.id.toString() }.toSet()
            val pagesDir = File(cacheDir, "pages")
            if (pagesDir.exists()) {
                pagesDir.listFiles()?.forEach { dir ->
                    if (dir.isDirectory && dir.name !in trackedIds) {
                        dir.deleteRecursively()
                    }
                }
            }
        }
    }
}
