package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A date choice belongs to one opening, selected civil day and captured phone zone. */
internal data class TimelineDateOpening(val original:LocalDate,val zone:ZoneId,val id:String=UUID.randomUUID().toString()) {
    companion object {
        fun saved(value:TimelineDateOpening):List<Any> = listOf(1,value.id,value.original.toString(),value.zone.id)
        fun restored(values:List<Any>):TimelineDateOpening? = runCatching {
            require(values.size==4 && values[0]==1)
            val id=values[1] as String;val day=values[2] as String;val zone=values[3] as String
            require(id.length==36 && UUID.fromString(id).toString()==id && day.length<=32 && zone.length<=64)
            TimelineDateOpening(LocalDate.parse(day),ZoneId.of(zone),id).also {timelineDayRange(it.original,it.zone)}
        }.getOrNull()
        val Saver=listSaver<TimelineDateOpening?,Any>(save={it?.let(::saved).orEmpty()},restore=::restored)
    }
}
