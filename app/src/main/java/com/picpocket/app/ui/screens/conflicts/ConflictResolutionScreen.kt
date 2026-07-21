package com.picpocket.app.ui.screens.conflicts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.picpocket.app.drive.sync.ConflictInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConflictResolutionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Conflicts") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.conflicts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No active conflicts",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.conflicts, key = { it.docId }) { conflict ->
                    ConflictCard(
                        conflict = conflict,
                        isResolving = conflict.docId in state.resolving,
                        onKeepLocal = { viewModel.keepLocal(conflict.docId) },
                        onKeepRemote = { viewModel.keepRemote(conflict.docId) },
                        onDismiss = { viewModel.dismiss(conflict.docId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: ConflictInfo,
    isResolving: Boolean,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    conflict.docId.take(8) + "...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Version mismatch: local v${conflict.localVersion?.syncVersion ?: "?"} vs Drive v${conflict.remoteVersion?.syncVersion ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Local: ${dateFormat.format(Date(conflict.localTimestamp))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Drive: ${dateFormat.format(Date(conflict.remoteTimestamp))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                conflict.localVersion?.let { local ->
                    Text("Local: ${local.name}", style = MaterialTheme.typography.bodySmall)
                    Text("Pages: ${local.pages.size}", style = MaterialTheme.typography.bodySmall)
                    Text("Modified: ${dateFormat.format(Date(local.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                conflict.remoteVersion?.let { remote ->
                    Text("Drive: ${remote.name}", style = MaterialTheme.typography.bodySmall)
                    Text("Pages: ${remote.pages.size}", style = MaterialTheme.typography.bodySmall)
                    Text("Modified: ${dateFormat.format(Date(remote.updatedAt))}", style = MaterialTheme.typography.bodySmall)
                }
            }

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show details")
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onKeepLocal,
                    enabled = !isResolving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Keep Local")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onKeepRemote,
                    enabled = !isResolving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Keep Drive")
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDismiss,
                enabled = !isResolving,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Dismiss")
            }
        }
    }
}
