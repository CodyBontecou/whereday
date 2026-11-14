package com.bontecou.whereday

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Native pickers decide whether location sharing is supported and consented; older pickers can redact. */
internal class PhotoPickerContract(private val requestLocations:()->Boolean):ActivityResultContract<PickVisualMediaRequest,List<Uri>>() {
    private val delegate=ActivityResultContracts.PickMultipleVisualMedia(50)
    override fun createIntent(context:Context,input:PickVisualMediaRequest)=delegate.createIntent(context,input).apply {requestPickerLocations(this,requestLocations())}
    override fun parseResult(resultCode:Int,intent:Intent?)=delegate.parseResult(resultCode,intent)
}

internal fun requestPickerLocations(intent:Intent,enabled:Boolean) {
    // Documented MediaStore extra added in 37.1. String form keeps the API36 build compatible.
    if(intent.action==MediaStore.ACTION_PICK_IMAGES) {
        if(enabled) intent.putExtra("android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS",true)
        else intent.removeExtra("android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS")
    }
}
