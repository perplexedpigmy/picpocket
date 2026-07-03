package com.docscanner.data.model

data class Document(
    val id: Long,
    val name: String,
    val outputUri: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int = 0,
    val totalFileSize: Long = 0,
)
