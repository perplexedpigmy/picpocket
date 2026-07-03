package com.docscanner.ui.screens.detail

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.docscanner.data.model.Page
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    documentId: Long,
    onNavigateBack: () -> Unit,
    viewModel: DocumentDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(documentId) {
        viewModel.loadDocument(documentId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.document?.name ?: "Document")
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleEditMode() }) {
                        Icon(
                            if (state.isEditMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (state.isEditMode) "Done editing" else "Edit",
                        )
                    }
                    IconButton(onClick = { viewModel.showRenameDialog() }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                state.document?.let { doc ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Name: ${doc.name}", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Created: ${formatDate(doc.createdAt)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Updated: ${formatDate(doc.updatedAt)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Pages: ${state.pages.size}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Pages", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(4.dp),
                ) {
                    itemsIndexed(state.pages) { index, page ->
                        PageThumbnail(
                            page = page,
                            pageNumber = index + 1,
                            isEditMode = state.isEditMode,
                            onDelete = { viewModel.deletePage(page.id) },
                        )
                    }
                }
            }
        }
    }

    if (state.showRenameDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideRenameDialog() },
            title = { Text("Rename document") },
            text = {
                OutlinedTextField(
                    value = state.renameText,
                    onValueChange = { viewModel.updateRenameText(it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renameDocument() }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideRenameDialog() }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun PageThumbnail(
    page: Page,
    pageNumber: Int,
    isEditMode: Boolean = false,
    onDelete: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .aspectRatio(0.7f),
    ) {
        Box {
            val bitmap = remember(page.imageUri) {
                try {
                    val file = File(Uri.parse(page.imageUri).path!!)
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page $pageNumber",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Failed to load", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "Page $pageNumber",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
            if (isEditMode) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                            shape = MaterialTheme.shapes.small,
                        ),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Delete page",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy  HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
