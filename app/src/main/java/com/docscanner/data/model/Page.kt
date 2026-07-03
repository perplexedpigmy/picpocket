package com.docscanner.data.model

data class Page(
    val id: Long,
    val documentId: Long,
    val pageNumber: Int,
    val imageUri: String,
    val ocrText: String? = null,
    val filterTypeOrdinal: Int = 0,
    val createdAt: Long,
)
