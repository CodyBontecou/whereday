package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.domain.Coordinate
import java.util.UUID

/** Logical presentation state only; never retains a native map, view or Activity. */
internal data class JournalMapCamera(
    val target:Coordinate,
    val zoom:Double,
    val bearing:Double,
    val tilt:Double,
    val fitted:Boolean,
    val appliedFocus:VisitMapFocus?,
    val appliedOutingFocus:String?=null,
) {
    fun saved():List<Any> = listOf(2,target.latitude,target.longitude,zoom,bearing,tilt,fitted,appliedOutingFocus.orEmpty()) +
        appliedFocus?.let {listOf(it.id,it.coordinate.latitude,it.coordinate.longitude,it.requestId)}.orEmpty()

    companion object {
        fun restored(values:List<Any>):JournalMapCamera? = runCatching {
            val version=values[0]
            require((version==1 && values.size in listOf(7,11)) || (version==2 && values.size in listOf(8,12)))
            fun number(index:Int)=(values[index] as Double).also {require(it.isFinite())}
            val target=Coordinate(number(1),number(2))
            val zoom=number(3).also {require(it>=0)}
            val bearing=number(4)
            val tilt=number(5).also {require(it>=0)}
            val fitted=values[6] as Boolean
            val offset=if(version==1) 7 else 8
            val outing=if(version==1) null else (values[7] as String).takeIf {it.isNotEmpty()}?.also {require(it.length==36 && UUID.fromString(it).toString()==it)}
            val focus=if(values.size==offset+4) VisitMapFocus(values[offset] as String,Coordinate(number(offset+1),number(offset+2)),values[offset+3] as String) else null
            JournalMapCamera(target,zoom,bearing,tilt,fitted,focus,outing)
        }.getOrNull()

        val Saver=listSaver<JournalMapCamera?,Any>(save={it?.saved().orEmpty()},restore=::restored)
    }
}
