package com.bontecou.whereday.platform

import kotlinx.coroutines.*

/** Process-owned recovery, independent of Activity and BOOT_COMPLETED delivery.
 * Starts await this owner before creating cancellation tickets. A failed attempt stays
 * retryable; cancellation of an Activity waiter cannot cancel the shared persistence work.
 */
internal class LocationRecoveryController(
    private val recover: suspend () -> Unit,
    private val onFailure: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val monitor = Any()
    private var current: Deferred<Unit>? = null

    fun start() { attempt(recheck = false) }
    suspend fun awaitReady() { attempt(recheck = false).await() }
    suspend fun reconcile() { attempt(recheck = true).await() }

    private fun attempt(recheck: Boolean): Deferred<Unit> = synchronized(monitor) {
        // Lifecycle calls share an in-flight attempt. Successful initial recovery is cached
        // for starts; an explicit lifecycle check can recheck later abandoned intent.
        current?.takeIf { !it.isCompleted || (!recheck && !it.isCancelled) } ?: scope.async(start = CoroutineStart.LAZY) {
            try { recover() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { runCatching(onFailure); throw error }
        }.also { current = it; it.start() }
    }

    internal suspend fun close() { scope.coroutineContext.job.cancelAndJoin() }
}
