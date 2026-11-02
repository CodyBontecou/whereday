package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.domain.Coordinate

/** Logical presentation state only; never retains a native map, view or Activity. */
internal data class JournalMapCamera(
    val target:Coordinate,
    val zoom:Double,
    val bearing:Double,
    val tilt:Double,
    val fitted:Boolean,
    val appliedFocus:VisitMapFocus?,
) {
    fun saved():List<Any> = listOf(1,target.latitude,target.longitude,zoom,bearing,tilt,fitted) +
        appliedFocus?.let {listOf(it.id,it.coordinate.latitude,it.coordinate.longitude,it.requestId)}.orEmpty()

    companion object {
        fun restored(values:List<Any>):JournalMapCamera? = runCatching {
            require(values.size==7 || values.size==11)
            require(values[0]==1)
            fun number(index:Int)=(values[index] as Double).also {require(it.isFinite())}
            val target=Coordinate(number(1),number(2))
            val zoom=number(3).also {require(it>=0)}
            val bearing=number(4)
            val tilt=number(5).also {require(it>=0)}
            val fitted=values[6] as Boolean
            val focus=if(values.size==11) VisitMapFocus(values[7] as String,Coordinate(number(8),number(9)),values[10] as String) else null
            JournalMapCamera(target,zoom,bearing,tilt,fitted,focus)
        }.getOrNull()

        val Saver=listSaver<JournalMapCamera?,Any>(save={it?.saved().orEmpty()},restore=::restored)
    }
}
