package com.bontecou.whereday.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class OnboardingStartFailed:IllegalStateException("Recording could not start.")
class OnboardingSaveFailed:IllegalStateException("Setup could not be saved.")

/** A visible user action owns the start; a committed setup change owns its completion event.
 * Cancellation before the service acknowledgment never commits onboarding. After acknowledgment,
 * finish the small durable save even if its successful recomposition disposes the calling screen.
 */
suspend fun completeOnboarding(start:Boolean,startRecording:suspend()->Unit,save:suspend()->Unit,onSaved:()->Unit) {
    if(start) try {startRecording()}
    catch(error:CancellationException) {throw error}
    catch(_:Exception) {throw OnboardingStartFailed()}
    withContext(NonCancellable) {
        try {save()}
        catch(_:Exception) {throw OnboardingSaveFailed()}
        onSaved()
    }
}
