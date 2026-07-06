package com.docscanner.ui.screens.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.docscanner.domain.filter.FilterType
import androidx.documentfile.provider.DocumentFile
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    documentId: Long? = null,
    onNavigateBack: () -> Unit,
    onDocumentSaved: (Long) -> Unit,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current as Activity

    LaunchedEffect(documentId) {
        if (documentId != null) {
            viewModel.setExistingDocumentId(documentId)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.getScanIntentSender(context)
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.handleScannerIntent(result.data)
    }

    LaunchedEffect(state.pendingIntentSender) {
        state.pendingIntentSender?.let { sender ->
            val request = IntentSenderRequest.Builder(sender).build()
            scannerLauncher.launch(request)
            viewModel.clearPendingIntentSender()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val pickedDir = DocumentFile.fromTreeUri(context, uri)
            val docFile = pickedDir?.createFile("application/pdf", state.documentName.replace(" ", "_"))
            if (docFile != null) {
                viewModel.saveDocument(docFile.uri)
            }
        }
    }

    LaunchedEffect(state.savedDocumentId) {
        state.savedDocumentId?.let { docId ->
            onDocumentSaved(docId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isAppendMode) "Add Pages" else "Scan Document") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!state.isAppendMode && state.capturedPages.isNotEmpty()) {
                        IconButton(onClick = { viewModel.showNameDialog() }) {
                            Icon(Icons.Default.Save, contentDescription = "Save document")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            if (state.isSaving) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Processing pages, running OCR, generating PDF...")
                    }
                }
            } else {
                if (state.isAppendMode) {
                    Text(
                        "Pages are auto-saved to your document",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.getScanIntentSender(context)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isScanning && !state.isSaving,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.isAppendMode) "Scan Next Page"
                        else if (state.capturedPages.isEmpty()) "Scan First Page"
                        else "Add Another Page"
                    )
                }

                if (state.isScanning) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                Spacer(Modifier.height(16.dp))

                if (state.scanError != null) {
                    Text(
                        "Error: ${state.scanError}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (state.capturedPages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.isAppendMode) "Tap the button above to add a new page"
                            else "Tap the button above to start scanning",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                } else {
                    Text(
                        if (state.isAppendMode) "${state.appendPageCount} page(s) added in this session"
                        else "${state.capturedPages.size} page(s) captured",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        itemsIndexed(state.capturedPages) { index, page ->
                            val bitmap = remember(page.imageUri) {
                                viewModel.loadBitmap(page.imageUri)
                            }
                            Card(
                                modifier = Modifier
                                    .width(160.dp)
                                    .aspectRatio(0.7f),
                            ) {
                                Box {
                                    if (bitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Page ${index + 1}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.TopCenter)
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Page ${index + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        Row {
                                            IconButton(
                                                onClick = { viewModel.showFilterSheet(index) },
                                                modifier = Modifier.size(24.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.FilterList,
                                                    contentDescription = "Filters",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                            IconButton(
                                                onClick = { viewModel.removePage(index) },
                                                modifier = Modifier.size(24.dp),
                                            ) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "Remove page",
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.saveError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Error: ${state.saveError}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (state.showNameDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideNameDialog() },
            title = { Text("Name your document") },
            text = {
                OutlinedTextField(
                    value = state.documentName,
                    onValueChange = { viewModel.updateDocumentName(it) },
                    label = { Text("Document name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            viewModel.hideNameDialog()
                            saveLauncher.launch(null)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.hideNameDialog()
                        saveLauncher.launch(null)
                    },
                    enabled = state.documentName.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideNameDialog() }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.showFilterSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { viewModel.hideFilterSheet() },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("Image Filters", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                FilterType.entries.forEach { filterType ->
                    val isActive = state.currentPageIndex in state.capturedPages.indices &&
                            filterType in state.capturedPages[state.currentPageIndex].filterTypes
                    FilledTonalButton(
                        onClick = { viewModel.applyFilter(state.currentPageIndex, filterType) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        if (isActive) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(filterType.label)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
