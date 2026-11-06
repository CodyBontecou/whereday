package com.bontecou.whereday.platform

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.resumeWithException

enum class PhotoLibraryAccess { None, Partial, Full }

/** Permission state is read from Android each time, never persisted as an entitlement. */
class AndroidPhotoAccess(private val context:Context) {
    fun current()=resolve(Build.VERSION.SDK_INT) {ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED}
    fun requestPermissions()=permissions(Build.VERSION.SDK_INT)
    fun originalLocationsAllowed()=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_MEDIA_LOCATION)==PackageManager.PERMISSION_GRANTED
    companion object {
        internal fun resolve(sdk:Int,granted:(String)->Boolean):PhotoLibraryAccess=when {
            sdk>=33 && granted(Manifest.permission.READ_MEDIA_IMAGES)->PhotoLibraryAccess.Full
            sdk>=34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)->PhotoLibraryAccess.Partial
            sdk<=32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE)->PhotoLibraryAccess.Full
            else->PhotoLibraryAccess.None
        }
        internal fun permissions(sdk:Int)=when {
            sdk>=34->arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            sdk>=33->arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else->arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

/** Permission-filtered MediaStore images only; no video, file traversal or network enumeration. */
class AndroidPhotoLibrary internal constructor(private val resolver:ContentResolver,private val collection:Uri,private val sdk:Int,private val access:()->PhotoLibraryAccess) {
    constructor(context:Context):this(context.applicationContext.contentResolver,MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),Build.VERSION.SDK_INT,AndroidPhotoAccess(context.applicationContext)::current)
    suspend fun references(consume:suspend(String)->Unit)=references(null,consume)
    suspend fun references(range:com.bontecou.whereday.domain.HistoryRange?,consume:suspend(String)->Unit)=withContext(Dispatchers.IO) {
        val initial=access()
        checkAccess(initial,initial)
        var after=-1L
        while(true) {
            currentCoroutineContext().ensureActive();checkAccess(initial,access())
            val args=Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION,"${MediaStore.Images.Media._ID}>? AND ${MediaStore.Images.Media.IS_PENDING}=0"+(if(sdk>=30) " AND ${MediaStore.Images.Media.IS_TRASHED}=0" else "")+(if(range!=null) " AND ${MediaStore.Images.Media.DATE_TAKEN}>=? AND ${MediaStore.Images.Media.DATE_TAKEN}<?" else ""))
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,if(range==null) arrayOf(after.toString()) else arrayOf(after.toString(),range.from.toEpochMilli().toString(),range.until.toEpochMilli().toString()))
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,arrayOf(MediaStore.Images.Media._ID))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
                putInt(ContentResolver.QUERY_ARG_LIMIT,500)
            }
            val cursor=query(args) ?: error("Photo library returned no query result")
            val page=ArrayList<Long>(500)
            cursor.use {
                val column=it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while(page.size<500 && it.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    check(!it.isNull(column)) {"Photo library returned an empty identifier"}
                    val id=it.getLong(column)
                    check(id>=0 && id>(page.lastOrNull() ?: after)) {"Photo library identifiers are not ordered"}
                    page+=id
                }
            }
            checkAccess(initial,access())
            if(page.isEmpty()) break
            for(id in page) {currentCoroutineContext().ensureActive();consume(ContentUris.withAppendedId(collection,id).toString())}
            checkAccess(initial,access())
            after=page.last()
            if(page.size<500) break
        }
    }
    private fun checkAccess(initial:PhotoLibraryAccess,current:PhotoLibraryAccess) {
        if(initial==PhotoLibraryAccess.None || current!=initial) throw SecurityException("Photo library access changed. Check access and sync again.")
    }
    private suspend fun query(args:Bundle):Cursor?=suspendCancellableCoroutine {continuation->
        val signal=CancellationSignal()
        continuation.invokeOnCancellation {signal.cancel()}
        try {
            val cursor=resolver.query(collection,arrayOf(MediaStore.Images.Media._ID),args,signal)
            continuation.resume(cursor,onCancellation={_,value,_->value?.close()})
        } catch(e:Exception) {if(continuation.isActive) continuation.resumeWithException(e)}
    }
}
