package com.picpocket.app.util

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtil {
    fun create(
        cacheDir: File,
        baseName: String,
        files: List<Pair<String, Uri>>,
        contentResolver: ContentResolver,
    ): File {
        val zipFile = File(cacheDir, "$baseName.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            for ((name, uri) in files) {
                contentResolver.openInputStream(uri)?.use { input ->
                    zos.putNextEntry(ZipEntry(name))
                    input.copyTo(zos)
                    zos.closeEntry()
                }
            }
        }
        return zipFile
    }
}
