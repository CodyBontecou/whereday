package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import kotlinx.coroutines.CompletableDeferred

/** Standalone test-APK activity. Only obtains the explicitly selected synthetic document grant. */
@Suppress("DEPRECATION")
class PhotoIdentityActivity:Activity() {
    companion object {@Volatile var result:CompletableDeferred<Uri?>?=null}
    private var granted:Uri?=null
    fun releaseSelection() {granted?.let {contentResolver.releasePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION)};granted=null}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null) window.decorView.post {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="image/*";addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,intent.getParcelableExtra<Uri>("initial"))
            },100)
        }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100) {
            val uri=data?.data?.takeIf {resultCode==RESULT_OK}
            if(uri!=null) {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);granted=uri}
            result?.complete(uri)
        }
    }
}
