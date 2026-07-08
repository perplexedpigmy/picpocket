package com.docscanner.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.docscanner.data.model.AutomationConfig
import com.docscanner.data.model.TagAutomation
import com.docscanner.data.model.TriggerEvent
import com.docscanner.data.model.WorkflowApp
import org.json.JSONObject

@Entity(
    tableName = "tag_automations",
    foreignKeys = [
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class TagAutomationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tagId: Long,
    val type: String,
    val configJson: String,
    val triggerEvent: String,
)

fun TagAutomationEntity.toDomain(): TagAutomation {
    val workflowApp = WorkflowApp.entries.first { it.value == type }
    val config = parseConfig(workflowApp, configJson)
    val event = TriggerEvent.entries.first { it.value == triggerEvent }
    return TagAutomation(
        id = id,
        tagId = tagId,
        app = workflowApp,
        config = config,
        triggerEvent = event,
    )
}

fun TagAutomation.toEntity(): TagAutomationEntity {
    return TagAutomationEntity(
        id = id,
        tagId = tagId,
        type = app.value,
        configJson = encodeConfig(config),
        triggerEvent = triggerEvent.value,
    )
}

internal fun parseConfig(app: WorkflowApp, json: String): AutomationConfig {
    if (json.isBlank() || json == "{}") return AutomationConfig.None
    val obj = JSONObject(json)
    return when (app) {
        WorkflowApp.WHATSAPP, WorkflowApp.TELEGRAM, WorkflowApp.VIBER -> AutomationConfig.None
        WorkflowApp.GOOGLE_DRIVE -> AutomationConfig.GoogleDrive(
            folderUri = obj.getString("folderUri"),
            displayName = obj.getString("displayName"),
        )
    }
}

internal fun encodeConfig(config: AutomationConfig): String {
    return when (config) {
        AutomationConfig.None -> "{}"
        is AutomationConfig.GoogleDrive -> JSONObject().apply {
            put("folderUri", config.folderUri)
            put("displayName", config.displayName)
        }.toString()
    }
}
