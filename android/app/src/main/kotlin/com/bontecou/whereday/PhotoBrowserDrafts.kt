package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.domain.HistoryRange
import java.time.Instant

internal data class PhotoBrowserHold(val owner:String,val range:HistoryRange) {
    companion object {
        fun saved(value:PhotoBrowserHold):List<Any> = listOf(1,value.owner,value.range.from.toString(),value.range.until.toString())
        fun restored(values:List<Any>):PhotoBrowserHold?=runCatching {
            require(values.size==4 && values[0]==1)
            PhotoBrowserHold(values[1] as String,HistoryRange(Instant.parse(values[2] as String),Instant.parse(values[3] as String)))
        }.getOrNull()
        val Saver=listSaver<PhotoBrowserHold?,Any>(save={it?.let(::saved).orEmpty()},restore=::restored)
    }
}

/** IDs and cursors are usable only after the new eligible directory matches this identity. */
internal data class PhotoBrowserOpening(val id:String,val snapshot:String,val areas:Set<String>?=null,val title:String="all",val place:Long?=null,val epoch:String="") {
    var browser=PhotoDirectoryDraft(snapshot,place)
        private set
    companion object {
        fun saved(value:PhotoBrowserOpening):List<Any> = listOf(1,value.id,value.snapshot,value.title,value.place ?: 0L,value.areas!=null,value.epoch,value.areas?.size ?: 0)+value.areas.orEmpty().sorted()+PhotoDirectoryDraft.saved(value.browser)
        fun restored(values:List<Any>):PhotoBrowserOpening?=runCatching {
            require(values.size in 16..521 && values[0]==1)
            val id=values[1] as String;val snapshot=values[2] as String;val title=values[3] as String;val place=values[4] as Long
            require(id.isNotBlank() && snapshot.matches(Regex("[a-f0-9]{64}")) && title in setOf("all","area","map") && place>=0)
            val hasAreas=values[5] as Boolean;val epoch=values[6] as String;val size=values[7] as Int;require(size in 0..500 && values.size>=8+size+8)
            val areas=values.subList(8,8+size).map {it as String}
            require(if(hasAreas) areas.isNotEmpty() && areas.size==areas.toSet().size && areas.all {it.isNotBlank()} else areas.isEmpty())
            PhotoBrowserOpening(id,snapshot,areas.toSet().takeIf {hasAreas},title,place.takeIf {it>0},epoch).apply {
                browser=checkNotNull(PhotoDirectoryDraft.restored(values.drop(8+size)));require(browser.snapshot==snapshot)
            }
        }.getOrNull()
        val Saver=listSaver<PhotoBrowserOpening?,Any>(save={it?.let(::saved).orEmpty()},restore=::restored)
    }
}

internal data class PhotoPageCursor(val page:Int=0,val after:Long=0) {
    init {require(page in 0..Int.MAX_VALUE/50 && after>=0 && (page==0)==(after==0L))}
    fun next(last:Long)=if(last>after) PhotoPageCursor(Math.addExact(page,1),last) else this
}

@Stable internal class PhotoDirectoryDraft(val snapshot:String,place:Long?) {
    var place by mutableStateOf(place)
    var directoryPage by mutableStateOf(PhotoPageCursor())
    var collectionPage by mutableStateOf(PhotoPageCursor())
    var photo by mutableStateOf<PhotoViewerDraft?>(null)
    fun select(id:Long?) {place=id;collectionPage=PhotoPageCursor();photo=null}
    companion object {
        fun saved(value:PhotoDirectoryDraft):List<Any> = listOf(1,value.snapshot,value.place ?: 0L,value.directoryPage.page,value.directoryPage.after,value.collectionPage.page,value.collectionPage.after,value.photo!=null)+value.photo?.let(PhotoViewerDraft::saved).orEmpty()
        fun restored(values:List<Any>):PhotoDirectoryDraft?=runCatching {
            require(values.size in setOf(8,13) && values[0]==1)
            val snapshot=values[1] as String;val place=values[2] as Long
            require(snapshot.matches(Regex("[a-f0-9]{64}")) && place>=0)
            PhotoDirectoryDraft(snapshot,place.takeIf {it>0}).apply {
                directoryPage=PhotoPageCursor(values[3] as Int,values[4] as Long)
                collectionPage=PhotoPageCursor(values[5] as Int,values[6] as Long)
                if(values[7] as Boolean) {require(values.size==13 && place>0);photo=checkNotNull(PhotoViewerDraft.restored(values.drop(8)));require(photo!!.owner=="$snapshot:$place")}
                else require(values.size==8)
            }
        }.getOrNull()
        val Saver=listSaver<PhotoDirectoryDraft,Any>(save={saved(it)},restore=::restored)
    }
}
