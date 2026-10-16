package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class SmartItem(
    @DocumentId val id: String = "",
    val title: String = "",
    val content: String = "",
    val category: String = "NOTE", // "NOTE", "TASK", "REMINDER"
    @get:PropertyName("isCompleted")
    @set:PropertyName("isCompleted")
    var isCompleted: Boolean = false,
    val scheduledTime: String = "",
    val userId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AutomationRoutine(
    @DocumentId val id: String = "",
    val title: String = "",
    val triggerPhrase: String = "",
    val iconName: String = "bolt",
    val actionSteps: List<String> = emptyList(),
    val userId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CommandLog(
    @DocumentId val id: String = "",
    val userQuery: String = "",
    val assistantReply: String = "",
    val executedActions: List<String> = emptyList(),
    val userId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
