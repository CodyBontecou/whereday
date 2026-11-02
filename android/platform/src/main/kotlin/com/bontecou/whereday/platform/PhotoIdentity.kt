package com.bontecou.whereday.platform

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException

/** Identity hints only. All metadata, thumbnails and grants continue using the original reference. */
internal class AndroidPhotoIdentity internal constructor(
    private val resolver:ContentResolver,
    private val sdk:Int,
    private val documentMedia:(Uri)->Uri?,
    private val storeVersion:(String)->String?
) {
    constructor(context:Context):this(context.applicationContext.contentResolver,Build.VERSION.SDK_INT,
        {uri->if(uri.authority in setOf("com.android.providers.media.documents","com.android.externalstorage.documents")) MediaStore.getMediaUri(context.applicationContext,uri) else null},
        {volume->if(Build.VERSION.SDK_INT>=30) MediaStore.getVersion(context.applicationContext,volume) else MediaStore.getVersion(context.applicationContext)})

    fun resolve(reference:Uri):String?=try {
        val media=if(isMediaCandidate(reference)) reference else documentMedia(reference)?.takeIf(::isMediaCandidate)
        if(media!=null) mediaIdentity(media)
        else resolver.canonicalize(reference)?.takeIf {it.scheme=="content" && !it.authority.isNullOrBlank()}?.let {canonical->
            if(isMediaCandidate(canonical)) mediaIdentity(canonical) else "provider:$canonical"
        }
    } catch(e:CancellationException) {throw e} catch(_:Exception) {null}

    private fun mediaIdentity(uri:Uri):String? {
        val columns=mutableListOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.VOLUME_NAME,MediaStore.MediaColumns.MIME_TYPE)
        if(sdk>=30) columns+=MediaStore.MediaColumns.GENERATION_ADDED
        return resolver.query(uri,columns.toTypedArray(),null,null,null)?.use {cursor->
            if(!cursor.moveToFirst()) return@use null
            val mime=cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            if(mime<0 || cursor.isNull(mime) || !cursor.getString(mime).startsWith("image/")) return@use null
            val id=cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            val volume=cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
            if(id<0 || volume<0 || cursor.isNull(id) || cursor.isNull(volume)) return@use null
            val number=cursor.getLong(id);val name=cursor.getString(volume)
            if(number<0 || name.isBlank()) return@use null
            val version=storeVersion(name)?.takeIf {it.isNotBlank()} ?: return@use null
            val generation=if(sdk>=30) {
                val column=cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_ADDED)
                if(column<0 || cursor.isNull(column)) return@use null
                cursor.getLong(column).takeIf {it>=0} ?: return@use null
            } else 0L
            // Length prefixes avoid collisions when a provider's version contains a delimiter.
            "media:${name.length}:$name:${version.length}:$version:$number:$generation"
        }
    }
    companion object {
        internal fun isMediaCandidate(uri:Uri):Boolean {
            val path=uri.pathSegments
            return uri.scheme=="content" && uri.authority==MediaStore.AUTHORITY && ((path.size==4 && path[1]=="images" && path[2]=="media" && path[3].toLongOrNull()?.let {it>=0}==true) || (path.size==3 && path[1]=="file" && path[2].toLongOrNull()?.let {it>=0}==true))
        }
    }
}
