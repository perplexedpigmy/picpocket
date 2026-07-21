package com.picpocket.app.ui.screens.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.picpocket.app.data.model.Tag
import com.picpocket.app.data.model.TagAutomation
import com.picpocket.app.data.model.TriggerEvent
import com.picpocket.app.data.model.WorkflowApp
import com.picpocket.app.ui.theme.TagColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDetailSheet(
    tag: Tag,
    automations: List<TagAutomation>,
    onRename: (String) -> Unit,
    onDeleteAutomation: (Long) -> Unit,
    onAddWorkflow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var editingName by remember(tag) { mutableStateOf(tag.name) }
    var isEditing by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            val dotColor = TagColors.getOrElse(tag.colorIndex) { TagColors[0] }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(10.dp))
                if (isEditing) {
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        onRename(editingName.trim())
                        isEditing = false
                    }) { Text("Save") }
                    TextButton(onClick = { isEditing = false; editingName = tag.name }) { Text("Cancel") }
                } else {
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isEditing = true },
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { isEditing = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit name",
                            modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Workflows", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            if (automations.isEmpty()) {
                Text(
                    "No workflows configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            } else {
                automations.forEach { automation ->
                    WorkflowRow(
                        automation = automation,
                        onDelete = { onDeleteAutomation(automation.id) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAddWorkflow) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add workflow")
            }
        }
    }
}

@Composable
private fun WorkflowRow(
    automation: TagAutomation,
    onDelete: () -> Unit,
) {
    val triggerLabel = when (automation.triggerEvent) {
        TriggerEvent.CREATE -> "On create"
        TriggerEvent.PAGES_ADDED -> "On pages added"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(appIconColor(automation.app)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                automation.app.displayName.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(automation.app.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(triggerLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Delete workflow",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error)
        }
    }
}


