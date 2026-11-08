package com.bontecou.whereday.platform

import com.bontecou.whereday.export.SavedPlaceImportPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Process-owned review: configuration changes must not lose it or put CSV rows in a Bundle. */
class SavedPlaceImportReview {
    private val current=MutableStateFlow<SavedPlaceImportPreview?>(null)
    val preview=current.asStateFlow()
    private var ticket:String?=null

    @Synchronized fun begin():String = UUID.randomUUID().toString().also {ticket=it;current.value=null}
    @Synchronized fun isCurrent(request:String)=ticket==request
    @Synchronized fun publish(request:String,value:SavedPlaceImportPreview):Boolean {
        if(ticket!=request) return false
        current.value=value
        return true
    }
    @Synchronized fun discard(request:String?=null) {
        if(request!=null && ticket!=request) return
        ticket=null
        current.value=null
    }
}
