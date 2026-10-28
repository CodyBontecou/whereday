package com.bontecou.whereday.platform

import android.content.*
import android.database.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore

/** Synthetic image IDs only, private to the instrumentation package. */
class PhotoLibraryTestProvider:ContentProvider() {
    companion object {
        var ids=listOf<Long>()
        var times=mapOf<Long,Long?>()
        var ignoreLimit=false
        var queryCount=0
        var onQuery:(CancellationSignal?)->Unit={}
        val arguments=mutableListOf<Bundle>()
        var closed=0
    }
    override fun onCreate()=true
    override fun getType(uri:Uri)="vnd.android.cursor.dir/image"
    override fun query(uri:Uri,projection:Array<out String>?,queryArgs:Bundle?,cancellationSignal:CancellationSignal?):Cursor {
        queryCount++;arguments+=Bundle(queryArgs);onQuery(cancellationSignal)
        val after=queryArgs!!.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)!!.first().toLong()
        val bounds=queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)!!
        val rows=ids.filter {id->id>after && (bounds.size==1 || times[id]?.let {it>=bounds[1].toLong() && it<bounds[2].toLong()}==true)}.let {if(ignoreLimit) it else it.take(queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT))}
        return object:MatrixCursor(arrayOf(MediaStore.Images.Media._ID)) {
            override fun close() {closed++;super.close()}
        }.apply {rows.forEach {addRow(arrayOf(it))}}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=throw UnsupportedOperationException()
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
