package com.bontecou.whereday.domain

import java.time.Instant

enum class ManualVisitMode { past, current }

/** A past visit always has a departure; current-place capture can remain open. */
data class VisitDraft(
    val id:String,val name:String,val address:String,val notes:String,val coordinate:Coordinate?,
    val arrivedAt:Instant,val departedAt:Instant?,val placeSource:String="userEntered"
) {
    fun selectLocation(selection:PlaceLocationSelection):VisitDraft {
        if(!selection.replaceMetadata) return copy(coordinate=selection.coordinate)
        val pickedName=selection.name?.takeIf {it.isNotBlank()}
        val pickedAddress=selection.address?.takeIf {it.isNotBlank()}
        val fallbackName=if(coordinate==null) name else ""
        val fallbackAddress=if(coordinate==null) address else ""
        val usedFallback=(pickedName==null && fallbackName.isNotBlank()) || (pickedAddress==null && fallbackAddress.isNotBlank())
        return copy(coordinate=selection.coordinate,name=pickedName ?: fallbackName,address=pickedAddress ?: fallbackAddress,
            placeSource=if(usedFallback) "userEntered" else selection.placeSource)
    }

    fun build(original:Visit?,now:Instant,confirm:Boolean=false,mode:ManualVisitMode=ManualVisitMode.past):Visit {
        require(original==null || original.id==id) {"The visit identity changed."}
        val location=requireNotNull(coordinate) {"Choose a location or enter valid coordinates."}
        val normalizedName=name.trim().ifEmpty {null};val normalizedAddress=address.trim().ifEmpty {null}
        require(original!=null || normalizedName!=null) {"Enter a place name."}
        require(original!=null || mode==ManualVisitMode.current || departedAt!=null) {"Past visits need a departure time."}
        require(arrivedAt<=now && (departedAt==null || departedAt in arrivedAt..now)) {"Enter past arrival and departure times. Departure must follow arrival."}
        if(original==null) return Visit(id,location,arrivedAt,departedAt,normalizedName,normalizedAddress,notes.ifBlank {null},
            VisitSource.manual,Confirmation.confirmed,confirmedAt=now,updatedAt=now,placeSource=placeSource)
        val placeChanged=location!=original.coordinate || normalizedName!=original.name || normalizedAddress!=original.address
        val base=if(placeChanged) original.correct(location,normalizedName,normalizedAddress,now).copy(placeSource=placeSource,placeDistanceMeters=null)
            else if(confirm && original.confirmation==Confirmation.unconfirmed) original.copy(confirmation=Confirmation.confirmed,confirmedAt=now)
            else original
        return base.copy(arrivedAt=arrivedAt,departedAt=departedAt,notes=notes.ifBlank {null},updatedAt=now,
            departureInferred=if(departedAt!=original.departedAt) false else original.departureInferred)
    }
}
