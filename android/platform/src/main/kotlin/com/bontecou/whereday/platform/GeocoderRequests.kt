package com.bontecou.whereday.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Country lookup transport seam; native calls may outlive a canceled coroutine. */
internal class GeocoderRequests(private val legacy:ThreadPoolExecutor=newLegacyExecutor()) {
    private val outstanding=AtomicInteger()
    suspend fun <T> callback(start:((T)->Unit,()->Unit)->Unit):T=suspendCancellableCoroutine {continuation->
        if(!continuation.isActive) return@suspendCancellableCoroutine
        if(outstanding.incrementAndGet()>4) {
            outstanding.decrementAndGet();continuation.resumeWithException(busy());return@suspendCancellableCoroutine
        }
        val finished=AtomicBoolean(false)
        fun finish(action:()->Unit) {
            if(!finished.compareAndSet(false,true)) return
            outstanding.decrementAndGet()
            if(continuation.isActive) action()
        }
        if(!continuation.isActive) {finish {};return@suspendCancellableCoroutine}
        // There is no native cancellation API. Keep the slot until actual completion,
        // even after timeout/Stop, so retries cannot accumulate stalled native requests.
        try {start({value->finish {continuation.resume(value)}},{finish {continuation.resumeWithException(unavailable())}})}
        catch(_:Exception) {finish {continuation.resumeWithException(unavailable())}}
    }
    suspend fun <T> blocking(load:()->T):T=suspendCancellableCoroutine {continuation->
        if(!continuation.isActive) return@suspendCancellableCoroutine
        val task=FutureTask {
            try {val value=load();if(continuation.isActive) continuation.resume(value)}
            catch(_:Exception) {if(continuation.isActive) continuation.resumeWithException(unavailable())}
        }
        continuation.invokeOnCancellation {
            // A running Binder call continues on the single worker. A queued call is removed.
            task.cancel(false);legacy.remove(task)
        }
        if(!continuation.isActive) return@suspendCancellableCoroutine
        try {
            legacy.execute(task)
            // Cover cancellation between registering the handler and entering the queue.
            if(!continuation.isActive) {task.cancel(false);legacy.remove(task)}
        } catch(_:RejectedExecutionException) {
            task.cancel(false)
            if(continuation.isActive) continuation.resumeWithException(busy())
        }
    }
    companion object {
        val shared by lazy {GeocoderRequests()}
        private fun busy()=IOException("The geocoding provider is busy. Try again later.")
        private fun unavailable()=IOException("Geocoder unavailable")
        fun newLegacyExecutor()=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),
            {task->Thread(task,"country-geocoder").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    }
}
