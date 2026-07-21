package com.picpocket.app.ui.screens.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.picpocket.app.data.model.DocumentId
import com.picpocket.app.domain.filter.FilterType
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.scan.QualityTier
import com.picpocket.app.ui.components.TagSelectorSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    documentId: DocumentId? = null,
    onNavigateBack: () -> Unit,
    onDocumentSaved: (DocumentId) -> Unit,
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

    LaunchedEffect(state.isAppendMode) {
        if (state.isAppendMode) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.getScanIntentSender(context)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
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

    val allTags by viewModel.allTags.collectAsState()

    LaunchedEffect(state.savedDocumentId) {
        state.savedDocumentId?.let { docId ->
            onDocumentSaved(docId)
        }
    }

    val hasPages = state.capturedPages.isNotEmpty() && !state.isAppendMode
    val dialogShowing = state.showNameDialog || state.showFilterSheet || state.showTagsDialog || state.showDiscardDialog

    BackHandler(enabled = hasPages && !dialogShowing) {
        viewModel.showDiscardDialog()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isAppendMode) "Add Pages" else "Scan Document") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasPages && !dialogShowing) {
                            viewModel.showDiscardDialog()
                        } else {
                            onNavigateBack()
                        }
                    }) {
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
        floatingActionButton = {
            if (!state.isSaving) {
                FloatingActionButton(
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
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Scan")
                }
            }
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
                        Text("Saving pages...")
                    }
                }
            } else {
                if (state.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(8.dp))
                }

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
                            if (state.isAppendMode) "Tap + to add a new page"
                            else "Tap + to scan your first page",
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

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(164.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        itemsIndexed(state.capturedPages) { index, page ->
                            val bitmap = remember(page.imageUri) {
                                viewModel.loadBitmap(page.imageUri)
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
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
        var showQualityMenu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.hideNameDialog() },
            title = { Text("Name your document") },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.documentName,
                        onValueChange = { viewModel.updateDocumentName(it) },
                        label = { Text("Document name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                viewModel.hideNameDialog()
                                viewModel.confirmNameAndSave()
                            }
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Quality",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            OutlinedButton(onClick = { showQualityMenu = true }) {
                                Text(state.qualityTier.label)
                            }
                            DropdownMenu(
                                expanded = showQualityMenu,
                                onDismissRequest = { showQualityMenu = false },
                            ) {
                                QualityTier.entries.forEach { tier ->
                                    DropdownMenuItem(
                                        text = { Text("${tier.label} (${tier.estimatedReduction})") },
                                        onClick = {
                                            viewModel.setQualityTier(tier)
                                            showQualityMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    var showPageSizeMenu by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Page Size",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            OutlinedButton(onClick = { showPageSizeMenu = true }) {
                                Text(state.exportPageSize.shortLabel)
                            }
                            DropdownMenu(
                                expanded = showPageSizeMenu,
                                onDismissRequest = { showPageSizeMenu = false },
                            ) {
                                PageSize.entries.forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text(size.label) },
                                        onClick = {
                                            viewModel.setExportPageSize(size)
                                            showPageSizeMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.hideNameDialog()
                        viewModel.confirmNameAndSave()
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

    if (state.showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideDiscardDialog() },
            title = { Text("Discard scanned pages?") },
            text = { Text("You have scanned pages that have not been saved. Going back will discard them.") },
            confirmButton = {
                TextButton(onClick = { onNavigateBack() }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideDiscardDialog() }) {
                    Text("Keep editing")
                }
            },
        )
    }

    if (state.showFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.hideFilterSheet() },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding(),
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

    if (state.showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelOverwrite() },
            title = { Text("Overwrite document?") },
            text = { Text("A document named \"${state.overwriteTargetName}\" already exists. Overwrite?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmOverwrite() }) {
                    Text("Overwrite")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelOverwrite() }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (state.showTagsDialog) {
        TagSelectorSheet(
            allTags = allTags,
            selectedTagIds = state.selectedTagIds,
            onToggleTag = { viewModel.toggleTag(it) },
            onCreateTag = { viewModel.createTagAndSelect(it) },
            onDone = { viewModel.completeSave() },
            onDismiss = { viewModel.completeSave() },
        )
    }
}
