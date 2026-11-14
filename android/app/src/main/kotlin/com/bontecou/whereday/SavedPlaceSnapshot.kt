package com.bontecou.whereday

import androidx.compose.runtime.saveable.listSaver
import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.SavedPlace
import com.bontecou.whereday.domain.SavedPlaceKind

/** Preserve the exact reviewed row, including null versus empty address and note, for conflict checks. */
internal object SavedPlaceSnapshot {
    fun saved(place:SavedPlace):List<Any> = listOf(2,place.id,place.name,place.coordinate.latitude,place.coordinate.longitude,place.address!=null,place.address.orEmpty(),place.radius,
        place.kind?.name.orEmpty(),place.notes!=null,place.notes.orEmpty())
    fun restored(values:List<Any>):SavedPlace? = runCatching {
        // Version 1 rows predate kinds and notes; they restore with neither.
        require((values.size==8 && values[0]==1) || (values.size==11 && values[0]==2))
        val kind=if(values.size==11) (values[8] as String).takeIf {it.isNotEmpty()}?.let(SavedPlaceKind::valueOf) else null
        val notes=if(values.size==11 && values[9] as Boolean) values[10] as String else null
        SavedPlace(values[1] as String,values[2] as String,Coordinate(values[3] as Double,values[4] as Double),if(values[5] as Boolean) values[6] as String else null,values[7] as Double,kind,notes)
    }.getOrNull()
    val Saver=listSaver<SavedPlace?,Any>(save={it?.let(::saved).orEmpty()},restore=::restored)
}
