package com.docscanner.data.model

enum class TriggerEvent(val value: String) {
    CREATE("create"),
    PAGES_ADDED("pages_added"),
}

enum class WorkflowApp(
    val value: String,
    val displayName: String,
    val packageName: String,
) {
    WHATSAPP("whatsapp", "WhatsApp", "com.whatsapp"),
    TELEGRAM("telegram", "Telegram", "org.telegram.messenger"),
    VIBER("viber", "Viber", "com.viber.voip"),
    GOOGLE_DRIVE("google_drive", "Google Drive", "com.google.android.apps.docs"),
}

sealed interface AutomationConfig {
    data object None : AutomationConfig
    data class GoogleDrive(val folderUri: String, val displayName: String) : AutomationConfig
}

data class TagAutomation(
    val id: Long,
    val tagId: Long,
    val app: WorkflowApp,
    val config: AutomationConfig,
    val triggerEvent: TriggerEvent,
)
