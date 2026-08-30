package com.shakeguard.feedback

import com.shakeguard.protection.EpochClock
import com.shakeguard.protection.SystemEpochClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class FeedbackHandler(
    private val repository: FeedbackRepository,
    private val allowanceStore: OneTimeAllowanceStore,
    private val clock: EpochClock = SystemEpochClock,
) {
    private val mutex = Mutex()

    suspend fun handle(command: FeedbackCommand): FeedbackResult = mutex.withLock {
        try {
            val target = repository.findFeedbackTarget(command.eventId)
                ?: return@withLock FeedbackResult.NotFound(command.eventId)
            if (target.eventId != command.eventId) {
                throw FeedbackTargetMismatchException(command.eventId)
            }

            when (command) {
                is FeedbackCommand.AllowOnce -> handleAllowOnce(command, target)
                is FeedbackCommand.AllowPair -> {
                    repository.applyAllowPair(target, clock.currentTimeMillis())
                    FeedbackResult.Applied(command)
                }
                is FeedbackCommand.ConfirmAd -> {
                    repository.applyConfirmAd(target, clock.currentTimeMillis())
                    FeedbackResult.Applied(command)
                }
                is FeedbackCommand.StopProtectingSource -> {
                    if (!repository.applyStopProtecting(target, clock.currentTimeMillis())) {
                        throw FeedbackSourceNotFoundException(target.sourcePackage)
                    }
                    FeedbackResult.Applied(command)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (cause: Exception) {
            FeedbackResult.Failed(command.eventId, cause)
        }
    }

    private suspend fun handleAllowOnce(
        command: FeedbackCommand.AllowOnce,
        target: FeedbackTarget,
    ): FeedbackResult {
        var granted = false
        try {
            allowanceStore.grant(target.sourcePackage, target.targetPackage)
            granted = true
            repository.markAllowOnce(target)
            return FeedbackResult.Applied(command)
        } catch (cancellation: CancellationException) {
            if (granted) revokeSafely(target)
            throw cancellation
        } catch (cause: Exception) {
            if (granted) revokeSafely(target)
            return FeedbackResult.Failed(command.eventId, cause)
        }
    }

    private suspend fun revokeSafely(target: FeedbackTarget) {
        try {
            withContext(NonCancellable) {
                allowanceStore.revoke(target.sourcePackage, target.targetPackage)
            }
        } catch (_: Throwable) {
            // Keep the original persistence or cancellation failure observable.
        }
    }
}

class FeedbackSourceNotFoundException(sourcePackage: String) :
    IllegalStateException("Protected source $sourcePackage was not found")
