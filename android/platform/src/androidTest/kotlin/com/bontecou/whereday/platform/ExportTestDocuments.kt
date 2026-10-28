package com.bontecou.whereday.platform

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/** Private instrumentation provider. Never packaged in the app; never touches shared storage. */
class ExportTestDocuments:DocumentsProvider() {
    companion object {
        val outputOpens=java.util.concurrent.atomic.AtomicInteger()
        val rejectDeletes=java.util.concurrent.atomic.AtomicBoolean(false)
        val rejectWrites=java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private val root get()=File(requireNotNull(context).cacheDir,"synthetic-export-documents").apply {mkdirs()}
    override fun onCreate()=true
    private fun file(id:String):File {
        if(id=="root") return root
        require(id.matches(Regex("[A-Za-z0-9_. ()-]+")))
        return File(root,id)
    }
    override fun queryRoots(projection:Array<out String>?):Cursor=MatrixCursor(arrayOf("root_id","document_id","title","flags")).apply {addRow(arrayOf<Any>("root","root","Synthetic export tests",0))}
    override fun queryDocument(documentId:String,projection:Array<out String>?):Cursor=documents(listOf(file(documentId)),projection)
    override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?):Cursor=documents(file(parentDocumentId).listFiles()?.toList().orEmpty(),projection)
    private fun documents(files:List<File>,projection:Array<out String>?):Cursor {
        val columns=projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_FLAGS,Document.COLUMN_LAST_MODIFIED)
        return MatrixCursor(columns).apply {
            files.filter {it.exists()}.forEach {f ->
                val values:Map<String,Any> =mapOf(Document.COLUMN_DOCUMENT_ID to if(f==root) "root" else f.name,
                    Document.COLUMN_DISPLAY_NAME to f.name,Document.COLUMN_MIME_TYPE to if(f.isDirectory) Document.MIME_TYPE_DIR else "application/json",
                    Document.COLUMN_SIZE to f.length(),Document.COLUMN_FLAGS to if(f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE,
                    Document.COLUMN_LAST_MODIFIED to f.lastModified())
                addRow(columns.map {values[it]}.toTypedArray())
            }
        }
    }
    override fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
        require(parentDocumentId=="root")
        val next=file(displayName)
        check(!next.exists())
        if(mimeType==Document.MIME_TYPE_DIR) check(next.mkdir()) else check(next.createNewFile())
        return displayName
    }
    override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
        check('w' !in mode || !rejectWrites.get()) {"Synthetic provider rejected writing"}
        if('w' in mode) outputOpens.incrementAndGet()
        val path=file(documentId)
        // Simulates a provider that closes the upload successfully but returns different bytes.
        if(documentId=="corrupt.json" && mode=="r") path.writeText("corrupted by synthetic provider")
        return ParcelFileDescriptor.open(path,ParcelFileDescriptor.parseMode(mode))
    }
    override fun deleteDocument(documentId:String) {check(!rejectDeletes.get()) {"Synthetic provider rejected deletion"};check(file(documentId).delete())}
    override fun isChildDocument(parentDocumentId:String,documentId:String)=parentDocumentId=="root" && file(documentId).parentFile==root
}
