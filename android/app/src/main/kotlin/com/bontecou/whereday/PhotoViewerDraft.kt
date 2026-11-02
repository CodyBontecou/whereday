package com.bontecou.whereday

import androidx.compose.runtime.*

/** Logical viewer identity survives validation/loading; a page ordinal never identifies an asset. */
@Stable internal class PhotoViewerDraft(val owner:String,val id:String,val opening:String,current:String=opening) {
    var current by mutableStateOf(current)
    companion object {
        fun open(owner:String,uri:String)=PhotoViewerDraft(owner,java.util.UUID.randomUUID().toString(),uri)
        fun saved(value:PhotoViewerDraft):List<Any> = listOf(1,value.owner,value.id,value.opening,value.current)
        fun restored(value:Any):PhotoViewerDraft? {
            // Earlier development builds saved only the library's original URI.
            if(value is String) return value.takeIf {it.startsWith("content://")}?.let {open("library",it)}
            val values=value as? List<*> ?: return null
            return runCatching {
            require(values.size==5 && values[0]==1)
            val owner=values[1] as String;val id=values[2] as String;val opening=values[3] as String;val current=values[4] as String
            require(owner.isNotBlank() && id.isNotBlank() && opening.startsWith("content://") && current.startsWith("content://"))
            PhotoViewerDraft(owner,id,opening,current)
            }.getOrNull()
        }
        val Saver=androidx.compose.runtime.saveable.Saver<PhotoViewerDraft?,Any>(
            save={it?.let {value->ArrayList(saved(value))} ?: arrayListOf<Any>()},restore=::restored)
    }
}
