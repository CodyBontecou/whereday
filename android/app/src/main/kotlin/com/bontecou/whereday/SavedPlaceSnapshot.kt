package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.SavedPlace

/** Preserve the exact reviewed row, including null versus empty address, for conflict checks. */
internal object SavedPlaceSnapshot {
    fun saved(place:SavedPlace):List<Any> = listOf(1,place.id,place.name,place.coordinate.latitude,place.coordinate.longitude,place.address!=null,place.address.orEmpty(),place.radius)
    fun restored(values:List<Any>):SavedPlace? = runCatching {
        require(values.size==8 && values[0]==1)
        SavedPlace(values[1] as String,values[2] as String,Coordinate(values[3] as Double,values[4] as Double),if(values[5] as Boolean) values[6] as String else null,values[7] as Double)
    }.getOrNull()
    val Saver=listSaver<SavedPlace?,Any>(save={it?.let(::saved).orEmpty()},restore=::restored)
}
