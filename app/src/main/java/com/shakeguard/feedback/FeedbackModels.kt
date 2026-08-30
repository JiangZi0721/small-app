package com.shakeguard.feedback

data class FeedbackTarget(
    val eventId: Long,
    val sourcePackage: String,
    val targetPackage: String,
)

sealed interface FeedbackCommand {
    val eventId: Long

    data class AllowOnce(override val eventId: Long) : FeedbackCommand
    data class AllowPair(override val eventId: Long) : FeedbackCommand
    data class ConfirmAd(override val eventId: Long) : FeedbackCommand
    data class StopProtectingSource(override val eventId: Long) : FeedbackCommand
}

sealed interface FeedbackResult {
    data class Applied(val command: FeedbackCommand) : FeedbackResult
    data class NotFound(val eventId: Long) : FeedbackResult
    data class Failed(val eventId: Long, val cause: Throwable) : FeedbackResult
}

interface FeedbackRepository {
    suspend fun findFeedbackTarget(eventId: Long): FeedbackTarget?
    suspend fun markAllowOnce(target: FeedbackTarget)
    suspend fun applyAllowPair(target: FeedbackTarget, updatedAt: Long): Long
    suspend fun applyConfirmAd(target: FeedbackTarget, updatedAt: Long): Long
    suspend fun applyStopProtecting(target: FeedbackTarget, updatedAt: Long): Boolean
    suspend fun countBlockedEvents(source: String, target: String): Int
}

class FeedbackEventNotFoundException(eventId: Long) :
    IllegalStateException("Feedback event $eventId was not found")

class FeedbackTargetMismatchException(eventId: Long) :
    IllegalArgumentException("Feedback target does not match event $eventId")
