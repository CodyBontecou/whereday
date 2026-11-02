package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PlayReviews {
    const val packageName="com.bontecou.whereday"
    val available get()=BuildConfig.PLAY_REVIEW_AVAILABLE
    fun installedFromPlay(context:Context):Boolean=runCatching {
        if(Build.VERSION.SDK_INT>=30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName=="com.android.vending"
        else {@Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)=="com.android.vending"}
    }.getOrDefault(false)
}
class PlayReviewHost internal constructor(private val activity:Activity,private val enabled:Boolean,private val manager:()->ReviewManager):ReviewPromptHost {
    constructor(activity:Activity):this(activity,PlayReviews.available && PlayReviews.installedFromPlay(activity),{ReviewManagerFactory.create(activity.applicationContext)})
    override fun ready()=enabled && !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
        (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
    override suspend fun prepare():ReviewPromptLaunch?=withContext(Dispatchers.Main.immediate) {
        if(!ready()) return@withContext null
        val review=manager();val info=withTimeout(10_000) {review.requestReviewFlow().awaitReview()}
        ReviewPromptLaunch {withContext(Dispatchers.Main.immediate) {if(ready()) review.launchReviewFlow(activity,info).awaitReview();Unit}}
    }
}
private suspend fun <T> Task<T>.awaitReview():T=suspendCancellableCoroutine {continuation->
    addOnSuccessListener {if(continuation.isActive) continuation.resume(it)}
    addOnFailureListener {if(continuation.isActive) continuation.resumeWithException(it)}
    addOnCanceledListener {continuation.cancel()}
}
