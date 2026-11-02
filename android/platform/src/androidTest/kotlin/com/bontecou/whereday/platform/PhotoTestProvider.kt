package com.bontecou.whereday.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

/** Private provider in the separate instrumentation APK. Never includes owner assets. */
class PhotoTestProvider:ContentProvider() {
    companion object {@Volatile var file:File?=null;@Volatile var captureTime:Long?=null;var originalFile:File?=null;var originalFailure:Exception?=null;var onOpen:()->Unit={};val opened=mutableListOf<Uri>();var canonical:Uri?=null}
    override fun onCreate()=true
    override fun canonicalize(uri:Uri):Uri?=canonical
    override fun getType(uri:Uri)="image/jpeg"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        if(uri.lastPathSegment=="revoked") throw SecurityException("Synthetic revoked photo")
        val columns=projection ?: arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN)
        return MatrixCursor(columns).apply {addRow(columns.map {if(it==MediaStore.Images.ImageColumns.DATE_TAKEN) captureTime else null})}
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        opened+=uri;onOpen()
        if(MediaStore.getRequireOriginal(uri)) {
            originalFailure?.let {throw it}
            return ParcelFileDescriptor.open(originalFile ?: throw FileNotFoundException("Synthetic original unavailable"),ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if(uri.lastPathSegment=="revoked") throw SecurityException("Synthetic revoked photo")
        if(uri.lastPathSegment=="missing") throw FileNotFoundException("Synthetic missing photo")
        return ParcelFileDescriptor.open(file ?: throw FileNotFoundException("No synthetic file"),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
