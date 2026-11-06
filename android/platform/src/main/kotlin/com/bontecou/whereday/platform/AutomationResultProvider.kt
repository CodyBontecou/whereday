package com.bontecou.whereday.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.util.UUID

/** Non-exported, read-only URI-grant endpoint; never a history query provider. */
class AutomationResultProvider:ContentProvider() {
    override fun onCreate()=true
    private fun id(uri:Uri):String {
        val context=requireNotNull(context)
        if(uri.scheme!="content" || uri.authority!="${context.packageName}.automation-results" || uri.pathSegments.size!=1 || uri.query!=null || uri.fragment!=null)
            throw FileNotFoundException("Automation result unavailable.")
        val id=uri.pathSegments.single()
        if(runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)!=true) throw FileNotFoundException("Automation result unavailable.")
        return id
    }
    private fun <T> read(signal:CancellationSignal?=null,block:suspend (AutomationResults,String?)->T):T {
        val context=requireNotNull(context)
        var produced:Any?=null
        val caller=callingPackage // Framework-verified recipient identity, never a URI parameter.
        return try {runBlocking {
            val operation=currentCoroutineContext()[Job]!!
            signal?.setOnCancelListener {operation.cancel()}
            try {signal?.throwIfCanceled();val graph=AppGraph.from(context);graph.journalClear.awaitReady();block(graph.automationResults,caller).also {produced=it}}
            finally {signal?.setOnCancelListener(null)}
        }} catch(error:Exception) {
            runCatching {(produced as? java.io.Closeable)?.close()}
            when(error) {
                is CancellationException,is OperationCanceledException ->throw OperationCanceledException()
                is SecurityException ->throw AutomationNotAuthorized()
                else ->throw FileNotFoundException("Automation result unavailable.")
            }
        }
    }
    // Android can call getType anonymously. Reveal no existence, name or per-file metadata.
    // The command result Intent supplies the actual type without this anonymous lookup.
    override fun getType(uri:Uri):String {id(uri);return "application/octet-stream"}
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=openFile(uri,mode,null)
    override fun openFile(uri:Uri,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
        if(mode!="r") throw FileNotFoundException("Automation results are read-only.")
        return read(signal) {store,caller ->store.open(caller,id(uri))}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor =
        query(uri,projection,selection,selectionArgs,sortOrder,null)
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?,signal:CancellationSignal?):Cursor {
        require(selection==null && selectionArgs==null && sortOrder==null) {"Automation result queries support metadata only."}
        val columns=projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        require(columns.isNotEmpty() && columns.distinct().size==columns.size && columns.all {it==OpenableColumns.DISPLAY_NAME || it==OpenableColumns.SIZE})
        return read(signal) {store,caller ->
            val info=store.info(caller,id(uri))
            MatrixCursor(columns.toTypedArray(),1).apply {addRow(columns.map {if(it==OpenableColumns.DISPLAY_NAME) info.name else info.bytes})}
        }
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri=throw UnsupportedOperationException("Read-only automation results.")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read-only automation results.")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException("Read-only automation results.")
}
