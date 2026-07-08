package com.docscanner.ui.screens.tags

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscanner.data.model.AutomationConfig
import com.docscanner.data.model.TagAutomation
import com.docscanner.data.model.TriggerEvent
import com.docscanner.data.model.WorkflowApp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkflowConfigSheet(
    tagId: Long,
    onSave: (TagAutomation) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    var selectedTrigger by remember { mutableStateOf(TriggerEvent.CREATE) }
    var selectedApp by remember { mutableStateOf<WorkflowApp?>(null) }

    var folderUri by remember { mutableStateOf("") }
    var folderDisplayName by remember { mutableStateOf("") }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            folderUri = uri.toString()
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        folderDisplayName = if (nameIndex >= 0) {
                            it.getString(nameIndex)
                        } else {
                            uri.lastPathSegment ?: "Folder"
                        }
                    }
                }
            } catch (_: Exception) {
                // SAF tree URIs for cloud providers may not support OpenableColumns
            }
            if (folderDisplayName.isBlank()) {
                folderDisplayName = uri.lastPathSegment ?: "Folder"
            }
        }
    }

    val pm = context.packageManager
    val installedAppIcons = remember {
        WorkflowApp.entries.map { app ->
            val icon = try {
                val drawable = pm.getApplicationIcon(app.packageName)
                if (drawable is BitmapDrawable) {
                    drawable.bitmap.asImageBitmap()
                } else {
                    val bmp = Bitmap.createBitmap(
                        drawable.intrinsicWidth.coerceAtLeast(48),
                        drawable.intrinsicHeight.coerceAtLeast(48),
                        Bitmap.Config.ARGB_8888,
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp.asImageBitmap()
                }
            } catch (_: Exception) { null }
            app to icon
        }.toMap()
    }

    val installedApps = remember {
        WorkflowApp.entries.filter { app ->
            try {
                pm.getPackageInfo(app.packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            Text("New Workflow", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(16.dp))
            Text("Trigger", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            TriggerEvent.entries.forEach { event ->
                val isSelected = event == selectedTrigger
                val label = when (event) {
                    TriggerEvent.CREATE -> "When document is created"
                    TriggerEvent.PAGES_ADDED -> "When pages are added"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedTrigger = event }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isSelected) "\u25C9" else "\u25CB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("App", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                installedApps.forEach { app ->
                    val isSelected = app == selectedApp
                    val iconBitmap = installedAppIcons[app]
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent,
                            )
                            .clickable {
                                selectedApp = app
                                folderUri = ""
                                folderDisplayName = ""
                            }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap,
                                    contentDescription = app.displayName,
                                    modifier = Modifier.size(44.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            } else {
                                Text(
                                    app.displayName.take(1),
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        Text(
                            app.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(64.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (selectedApp == WorkflowApp.GOOGLE_DRIVE) {
                Text("Target folder", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (folderUri.isNotBlank()) "Change folder" else "Pick folder")
                }
                if (folderDisplayName.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Selected: $folderDisplayName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap the \u2630 menu in the picker to switch to Google Drive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val app = selectedApp ?: return@Button
                    val config = when (app) {
                        WorkflowApp.GOOGLE_DRIVE -> AutomationConfig.GoogleDrive(
                            folderUri = folderUri,
                            displayName = folderDisplayName,
                        )
                        else -> AutomationConfig.None
                    }
                    onSave(
                        TagAutomation(
                            id = 0,
                            tagId = tagId,
                            app = app,
                            config = config,
                            triggerEvent = selectedTrigger,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedApp != null && when (selectedApp) {
                    WorkflowApp.GOOGLE_DRIVE -> folderUri.isNotBlank()
                    else -> true
                },
            ) {
                Text("Save")
            }
        }
    }
}

internal fun appIconColor(app: WorkflowApp): Color {
    return when (app) {
        WorkflowApp.WHATSAPP -> Color(0xFF25D366)
        WorkflowApp.TELEGRAM -> Color(0xFF229ED9)
        WorkflowApp.VIBER -> Color(0xFF7360F2)
        WorkflowApp.GOOGLE_DRIVE -> Color(0xFF4285F4)
    }
}
