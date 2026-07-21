package com.picpocket.app.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.scan.QualityTier
import com.picpocket.app.drive.DriveAuthState
import com.picpocket.app.drive.SyncState
import com.picpocket.app.ui.theme.DarkMode
import com.picpocket.app.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onDonateClick: () -> Unit = {},
    onTagsClick: () -> Unit = {},
    onConflictsClick: () -> Unit = {},
    onDeletedClick: () -> Unit = {},
    onPairingClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val driveState by viewModel.driveAuthState.collectAsState()
    var showPageSizeMenu by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }
    val folderPickerState by viewModel.folderPickerState.collectAsState()

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.handleSignInResult(result)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        viewModel.handleFolderPickerResult(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dark mode", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.selectableGroup()) {
                        DarkMode.entries.forEach { mode ->
                            Row(
                                Modifier
                                    .weight(1f)
                                    .selectable(
                                        selected = state.darkMode == mode,
                                        onClick = { viewModel.setDarkMode(mode) },
                                        role = Role.RadioButton,
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.darkMode == mode,
                                    onClick = null,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when (mode) {
                                        DarkMode.SYSTEM -> "System"
                                        DarkMode.LIGHT -> "Light"
                                        DarkMode.DARK -> "Dark"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Palette", modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.selectableGroup()) {
                        Palette.entries.forEach { palette ->
                            Row(
                                Modifier
                                    .weight(1f)
                                    .selectable(
                                        selected = state.palette == palette,
                                        onClick = { viewModel.setPalette(palette) },
                                        role = Role.RadioButton,
                                    )
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.palette == palette,
                                    onClick = null,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    when (palette) {
                                        Palette.ROYAL -> "Royal"
                                        Palette.DEFAULT -> "Blue"
                                        Palette.OCEAN -> "Teal"
                                        Palette.FOREST -> "Green"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Searchable PDF", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Embed OCR text layer for searchable PDFs",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.searchablePdf,
                            onCheckedChange = { viewModel.toggleSearchablePdf(it) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when (driveState) {
                                is DriveAuthState.Connected -> Icons.Default.Cloud
                                else -> Icons.Default.CloudOff
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when (driveState) {
                                    is DriveAuthState.Connected -> "Connected"
                                    is DriveAuthState.Disconnected -> "Not connected"
                                    is DriveAuthState.Connecting -> "Connecting..."
                                    is DriveAuthState.ReauthRequired -> "Re-authentication required"
                                    is DriveAuthState.Error -> "Error"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    when (driveState) {
                        is DriveAuthState.Connected -> {
                            Row {
                                Button(
                                    onClick = { viewModel.syncNow() },
                                    enabled = state.syncState !is SyncState.Syncing,
                                ) {
                                    if (state.syncState is SyncState.Syncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text("Sync Now")
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { viewModel.signOut() }) {
                                    Text("Disconnect")
                                }
                            }
                            if (state.syncState is SyncState.Error) {
                                Text(
                                    text = (state.syncState as SyncState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (!state.hasFolder) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { folderPickerLauncher.launch(null) }) {
                                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Select Drive folder")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Sync enabled", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = state.syncEnabled,
                                    onCheckedChange = { viewModel.toggleSync(it) },
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            EncryptionSection(
                                encryptionEnabled = viewModel.encryptionEnabled,
                                onEnableEncryption = { passphrase ->
                                    viewModel.setEncryptionPassphrase(passphrase)
                                },
                                onDisableEncryption = { viewModel.disableEncryption() },
                                onChangePassphrase = { newPassphrase ->
                                    viewModel.setEncryptionPassphrase(newPassphrase)
                                },
                            )
                        }
                        is DriveAuthState.Disconnected -> {
                            Button(onClick = { signInLauncher.launch(viewModel.signInIntent) }) {
                                Text("Connect Drive")
                            }
                        }
                        is DriveAuthState.ReauthRequired -> {
                            Button(onClick = { signInLauncher.launch(viewModel.signInIntent) }) {
                                Text("Reconnect Drive")
                            }
                        }
                        is DriveAuthState.Connecting -> {}
                        is DriveAuthState.Error -> {
                            Button(onClick = { signInLauncher.launch(viewModel.signInIntent) }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            if (driveState is DriveAuthState.Connected) {
                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onConflictsClick),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Sync Conflicts", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Resolve version conflicts between devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDeletedClick),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Deleted Documents", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Manage documents deleted by paired devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPairingClick),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.DevicesOther,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Paired Devices", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Manage device pairing for sync",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Default Page Size",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            OutlinedButton(onClick = { showPageSizeMenu = true }) {
                                Text(state.pageSize.label)
                            }
                            DropdownMenu(
                                expanded = showPageSizeMenu,
                                onDismissRequest = { showPageSizeMenu = false },
                            ) {
                                PageSize.entries.forEach { size ->
                                    DropdownMenuItem(
                                        text = { Text(size.label) },
                                        onClick = {
                                            viewModel.setPageSize(size)
                                            showPageSizeMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Default Scan Quality",
                            style = MaterialTheme.typography.titleMedium,
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.qualityTier.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTagsClick),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Manage Tags",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Create, rename, or delete tags. Manage workflows.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

                Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDonateClick),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Donate",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Support the project",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "PicPocket v1.0.0",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.dumpDocumentDir() }) {
                        Text("Debug: dump documents", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    when (val state = folderPickerState) {
        is FolderPickerState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissError() },
                title = { Text("Folder Selection") },
                text = { Text(state.message) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissError()
                        folderPickerLauncher.launch(null)
                    }) {
                        Text("Try Again")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissError() }) {
                        Text("Cancel")
                    }
                },
            )
        }
        else -> {}
    }
}

@Composable
private fun EncryptionSection(
    encryptionEnabled: Boolean,
    onEnableEncryption: (String) -> Unit,
    onDisableEncryption: () -> Unit,
    onChangePassphrase: (String) -> Unit,
) {
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var showDisableConfirm by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }
    var passphraseConfirm by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    if (showPassphraseDialog) {
        AlertDialog(
            onDismissRequest = {
                showPassphraseDialog = false
                passphraseInput = ""
                passphraseConfirm = ""
                showError = false
            },
            title = { Text(if (encryptionEnabled) "Change Passphrase" else "Set Encryption Passphrase") },
            text = {
                Column {
                    if (encryptionEnabled) {
                        Text(
                            "Enter a new passphrase to encrypt Drive files.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Set a passphrase to encrypt all Drive files and filenames.\n\nYour device must have a lock screen configured.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it; showError = false },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        isError = showError,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphraseConfirm,
                        onValueChange = { passphraseConfirm = it; showError = false },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = showError,
                    )
                    if (showError) {
                        Text(
                            "Passphrases do not match",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (passphraseInput == passphraseConfirm && passphraseInput.isNotBlank()) {
                            if (encryptionEnabled) {
                                onChangePassphrase(passphraseInput)
                            } else {
                                onEnableEncryption(passphraseInput)
                            }
                            showPassphraseDialog = false
                            passphraseInput = ""
                            passphraseConfirm = ""
                        } else {
                            showError = true
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPassphraseDialog = false
                    passphraseInput = ""
                    passphraseConfirm = ""
                    showError = false
                }) { Text("Cancel") }
            },
        )
    }

    if (showDisableConfirm) {
        AlertDialog(
            onDismissRequest = { showDisableConfirm = false },
            title = { Text("Disable Encryption?") },
            text = {
                Text("This will decrypt all previously synced Drive files. Continue?")
            },
            confirmButton = {
                TextButton(onClick = { onDisableEncryption(); showDisableConfirm = false }) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableConfirm = false }) { Text("Cancel") }
            },
        )
    }

    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text("Encryption", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        if (encryptionEnabled) "Drive files are encrypted at rest"
        else "Drive files are stored in cleartext",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (encryptionEnabled) {
        Row {
            OutlinedButton(onClick = { showPassphraseDialog = true }) {
                Text("Change Passphrase")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showDisableConfirm = true }) {
                Text("Disable")
            }
        }
    } else {
        OutlinedButton(onClick = { showPassphraseDialog = true }) {
            Text("Enable Encryption")
        }
    }
}
