package com.shakeguard.ui.activity

import com.shakeguard.feedback.FeedbackCommand

data class ActivityEventUiModel(
    val id: Long, val sourcePackage: String, val targetPackage: String, val elapsedMs: Long,
    val decision: String, val matchedRuleId: Long?, val actionResult: String,
    val userFeedback: String?, val createdAt: Long, val feedbackActions: List<FeedbackCommand>,
)

sealed interface ActivityLogEffect {
    data class Message(val text: String) : ActivityLogEffect
    data class EventMissing(val eventId: Long) : ActivityLogEffect
    data class Failure(val eventId: Long, val cause: Throwable) : ActivityLogEffect
}
