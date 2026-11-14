package com.bontecou.whereday

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Shell-only, fixed-mode presentation fixture. Never reads or writes the application billing graph. */
class QaSubscriptionPreviewProvider:ContentProvider() {
    override fun onCreate():Boolean {
        check(SubscriptionPreview.allowed(requireNotNull(context).packageName,BuildConfig.BUILD_TYPE))
        return true
    }
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        val mode=SubscriptionPreview.configure(requireNotNull(context).packageName,BuildConfig.BUILD_TYPE,
            Binder.getCallingUid(),method,arg,extras!=null && !extras.isEmpty)
        return Bundle().apply {putString("presentation",mode)}
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):android.database.Cursor?=throw UnsupportedOperationException()
    override fun getType(uri:Uri):String=throw UnsupportedOperationException()
    override fun insert(uri:Uri,values:ContentValues?):Uri=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
