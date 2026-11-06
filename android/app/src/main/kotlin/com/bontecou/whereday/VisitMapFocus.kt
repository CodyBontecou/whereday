package com.bontecou.whereday

import com.bontecou.whereday.domain.*
import java.time.ZoneId
import java.util.UUID

/** A saved-record map target; it never changes the visit or requests a location fix. */
data class VisitMapFocus(val id:String,val coordinate:Coordinate,val requestId:String=UUID.randomUUID().toString()) {
    fun bounds():MapCameraBounds {
        fun longitude(value:Double)=((value+180)%360+360)%360-180
        return requireNotNull(MapCameraBounds.enclosing(listOf(
            Coordinate((coordinate.latitude-0.004).coerceIn(-90.0,90.0),longitude(coordinate.longitude-0.004)),
            Coordinate((coordinate.latitude+0.004).coerceIn(-90.0,90.0),longitude(coordinate.longitude+0.004)),
        )))
    }
}
internal fun visitMapSelection(visit:Visit,zone:ZoneId):MapSelection {
    val day=visit.arrivedAt.atZone(zone).toLocalDate()
    val start=day.atStartOfDay(zone).toInstant()
    // The Swift focus method selects start...next midnight, including that final instant.
    val end=day.plusDays(1).atStartOfDay(zone).toInstant().plusMillis(1)
    require(runCatching {start.toEpochMilli();end.toEpochMilli()}.isSuccess) {"Visit date is outside the supported map range"}
    return MapSelection("Visit",outingRange=HistoryRange(start,end),visitFocus=VisitMapFocus(visit.id,visit.coordinate))
}
