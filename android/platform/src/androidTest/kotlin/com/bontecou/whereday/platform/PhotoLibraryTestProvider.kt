package com.bontecou.whereday.platform

import android.content.*
import android.database.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore

/** Synthetic image IDs only, private to the instrumentation package. */
class PhotoLibraryTestProvider:ContentProvider() {
    companion object {
        private class Probe {
            var ids=listOf<Long>();var times=mapOf<Long,Long?>();var ignoreLimit=false
            var onQuery:(CancellationSignal?)->Unit={}
            val queries=java.util.concurrent.atomic.AtomicInteger()
            val closed=java.util.concurrent.atomic.AtomicInteger()
            val arguments=java.util.Collections.synchronizedList(mutableListOf<Bundle>())
        }
        @Volatile private var probe=Probe()
        fun reset() {probe=Probe()}
        var ids:List<Long> get()=probe.ids;set(value) {probe.ids=value}
        var times:Map<Long,Long?> get()=probe.times;set(value) {probe.times=value}
        var ignoreLimit:Boolean get()=probe.ignoreLimit;set(value) {probe.ignoreLimit=value}
        var onQuery:(CancellationSignal?)->Unit get()=probe.onQuery;set(value) {probe.onQuery=value}
        val queryCount:Int get()=probe.queries.get()
        val closed:Int get()=probe.closed.get()
        val arguments:MutableList<Bundle> get()=probe.arguments
    }
    override fun onCreate()=true
    override fun getType(uri:Uri)="vnd.android.cursor.dir/image"
    override fun query(uri:Uri,projection:Array<out String>?,queryArgs:Bundle?,cancellationSignal:CancellationSignal?):Cursor {
        val owner=probe
        owner.queries.incrementAndGet();owner.arguments+=Bundle(queryArgs);owner.onQuery(cancellationSignal)
        val after=queryArgs!!.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)!!.first().toLong()
        val bounds=queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)!!
        val rows=owner.ids.filter {id->id>after && (bounds.size==1 || owner.times[id]?.let {it>=bounds[1].toLong() && it<bounds[2].toLong()}==true)}.let {if(owner.ignoreLimit) it else it.take(queryArgs.getInt(ContentResolver.QUERY_ARG_LIMIT))}
        return object:MatrixCursor(arrayOf(MediaStore.Images.Media._ID)) {
            private val released=java.util.concurrent.atomic.AtomicBoolean()
            override fun close() {
                // A cursor belongs to its creating probe; Closeable disposal is idempotent.
                if(released.compareAndSet(false,true)) {owner.closed.incrementAndGet();super.close()}
            }
        }.apply {rows.forEach {addRow(arrayOf(it))}}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=throw UnsupportedOperationException()
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
