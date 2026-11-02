package com.bontecou.whereday.automationclient

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.util.UUID

/** Random-token synthetic receipts for the native test runner; never bundled with Whereday. */
class ReceiptProvider:ContentProvider() {
    override fun onCreate()=true
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val token=uri.lastPathSegment ?: error("Missing fixture token")
        require(UUID.fromString(token).toString()==token)
        val result=requireNotNull(context).getSharedPreferences("receipts",Context.MODE_PRIVATE).getString(token,null)
        return MatrixCursor(arrayOf("receipt")).apply {if(result!=null) addRow(arrayOf(result))}
    }
    override fun getType(uri:Uri)="application/json"
    override fun insert(uri:Uri,values:ContentValues?):Uri=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
